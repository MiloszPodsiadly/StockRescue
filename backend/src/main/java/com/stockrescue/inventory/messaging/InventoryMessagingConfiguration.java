package com.stockrescue.inventory.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.TaskScheduler;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Configuration(proxyBeanMethods=false)
public class InventoryMessagingConfiguration {
    @Bean
    NewTopic inventoryEventsTopic(@Value("${stockrescue.inventory.messaging.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    ConsumerFactory<String,String> inventoryConsumerFactory(KafkaProperties properties) {
        var settings=properties.buildConsumerProperties();
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        settings.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        return new DefaultKafkaConsumerFactory<>(settings);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String,String> inventoryKafkaListenerContainerFactory(
            ConsumerFactory<String,String> inventoryConsumerFactory,DefaultErrorHandler inventoryConsumerErrorHandler,
            InventoryMessagingMetrics metrics) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(inventoryConsumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(inventoryConsumerErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedAfterCommit(Consumer<?,?> consumer,Collection<TopicPartition> partitions) {
                metrics.consumerPartitionsRevoked(partitions);
            }

            @Override
            public void onPartitionsLost(Consumer<?,?> consumer,Collection<TopicPartition> partitions) {
                metrics.consumerPartitionsRevoked(partitions);
            }
        });
        return factory;
    }

    @Bean
    ListenerContainerPauseService inventoryListenerPauseService(KafkaListenerEndpointRegistry listeners,
            TaskScheduler taskScheduler) {
        return new ListenerContainerPauseService(listeners,taskScheduler);
    }

    @Bean
    DefaultErrorHandler inventoryConsumerErrorHandler(InventoryMessagingMetrics metrics,
            ListenerContainerPauseService pauseService,
            @Value("${stockrescue.inventory.messaging.consumer-retry-initial:1s}") Duration retryInitial,
            @Value("${stockrescue.inventory.messaging.consumer-retry-maximum:30s}") Duration retryMaximum,
            @Value("${stockrescue.inventory.messaging.consumer-blocked-retry-interval:5m}") Duration blockedRetryInterval) {
        long initialMillis=retryInitial.toMillis();
        long maximumMillis=retryMaximum.toMillis();
        long blockedMillis=blockedRetryInterval.toMillis();
        if(initialMillis<1 || maximumMillis<initialMillis || maximumMillis>Duration.ofMinutes(5).toMillis()
                || blockedMillis<1 || blockedMillis>Duration.ofHours(1).toMillis())
            throw new IllegalArgumentException("Inventory consumer retry intervals are invalid");
        var handler=new DefaultErrorHandler((record,failure)->{
            throw new KafkaException("Inventory event was not durably processed; retaining its Kafka offset",failure);
        },infiniteJitteredBackOff(initialMillis,maximumMillis),new ContainerPausingBackOffHandler(pauseService));
        handler.setClassifications(Map.ofEntries(
                Map.entry(InventoryConsumerFailure.class,true),
                Map.entry(ListenerExecutionFailedException.class,true),
                Map.entry(DeserializationException.class,true),
                Map.entry(RecordDeserializationException.class,true),
                Map.entry(SerializationException.class,true),
                Map.entry(MessageConversionException.class,true),
                Map.entry(ConversionException.class,true),
                Map.entry(MethodArgumentResolutionException.class,true),
                Map.entry(ClassCastException.class,true),
                Map.entry(org.springframework.kafka.KafkaException.class,true),
                Map.entry(org.apache.kafka.common.KafkaException.class,true)
        ),false);
        handler.setBackOffFunction((record,failure)->InventoryConsumerFailure.categoryOf(failure)
                ==InventoryConsumerFailure.Category.TRANSIENT_INFRA
                ?infiniteJitteredBackOff(initialMillis,maximumMillis):infiniteFixedBackOff(blockedMillis));
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        handler.setSeekAfterError(true);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record,
                    Exception failure,int deliveryAttempt) {
                metrics.consumerRetrying(record,InventoryConsumerFailure.categoryOf(failure),deliveryAttempt);
            }

            @Override
            public void recovered(org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record,Exception failure) {
                metrics.consumerRetryRecovered(record);
            }

            @Override
            public void recoveryFailed(org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record,
                    Exception failure,Exception recoveryFailure) {
                metrics.consumerRetryRecoveryFailed(record);
            }
        });
        return handler;
    }

    private static BackOff infiniteJitteredBackOff(long initialMillis,long maximumMillis) {
        return ()->new BackOffExecution() {
            private int exponent;

            @Override
            public long nextBackOff() {
                int shift=Math.min(exponent,30);
                if(exponent<30) exponent++;
                long multiplier=1L<<shift;
                long ceiling=initialMillis>maximumMillis/multiplier?maximumMillis:Math.min(maximumMillis,initialMillis*multiplier);
                long spread=Math.max(1,ceiling/4);
                long minimum=Math.max(1,ceiling-spread);
                long maximum=Math.min(maximumMillis,ceiling+spread);
                return maximum<=minimum?ceiling:ThreadLocalRandom.current().nextLong(minimum,maximum+1);
            }
        };
    }

    private static BackOff infiniteFixedBackOff(long intervalMillis) {
        return ()->new BackOffExecution() {
            @Override public long nextBackOff() { return intervalMillis; }
        };
    }
}
