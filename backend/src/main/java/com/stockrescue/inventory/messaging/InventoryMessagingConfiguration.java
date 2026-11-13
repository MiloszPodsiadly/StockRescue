package com.stockrescue.inventory.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.KafkaException;

import java.time.Duration;
import java.util.Map;

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
            ConsumerFactory<String,String> inventoryConsumerFactory,DefaultErrorHandler inventoryConsumerErrorHandler) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(inventoryConsumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(inventoryConsumerErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    @Bean
    DefaultErrorHandler inventoryConsumerErrorHandler(InventoryMessagingMetrics metrics,
            @Value("${stockrescue.inventory.messaging.consumer-retry-interval:1s}") Duration retryInterval) {
        long retryMillis=retryInterval.toMillis();
        if(retryMillis<1) throw new IllegalArgumentException("Inventory consumer retry interval must be at least one millisecond");
        var handler=new DefaultErrorHandler((record,failure)->{
            metrics.consumerRetryRecoveryFailed(record);
            throw new KafkaException("Inventory event was not durably processed; retaining its Kafka offset",failure);
        },new FixedBackOff(retryMillis,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(),true);
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        handler.setSeekAfterError(true);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record,
                    Exception failure,int deliveryAttempt) {
                metrics.consumerRetrying(record);
            }
        });
        return handler;
    }
}
