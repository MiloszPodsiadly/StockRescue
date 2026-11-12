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
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.apache.kafka.clients.admin.NewTopic;

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
            ConsumerFactory<String,String> inventoryConsumerFactory) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(inventoryConsumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
