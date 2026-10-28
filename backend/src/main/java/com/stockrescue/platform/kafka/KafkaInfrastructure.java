package com.stockrescue.platform.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaInfrastructure {

    public static final String SMOKE_TOPIC = "stockrescue.infrastructure.smoke.v1";

    @Bean
    NewTopic infrastructureSmokeTopic() {
        return TopicBuilder.name(SMOKE_TOPIC).partitions(1).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "3600000").build();
    }
}
