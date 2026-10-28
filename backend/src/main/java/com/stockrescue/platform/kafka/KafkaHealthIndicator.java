package com.stockrescue.platform.kafka;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin admin;

    public KafkaHealthIndicator(KafkaAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        try {
            return admin.describeTopics(KafkaInfrastructure.SMOKE_TOPIC)
                    .containsKey(KafkaInfrastructure.SMOKE_TOPIC) ? Health.up().build() : Health.down().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }
}
