package com.stockrescue.platform.intelligence;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("intelligence")
public class IntelligenceHealthIndicator implements HealthIndicator {

    private final IntelligenceClient client;

    public IntelligenceHealthIndicator(IntelligenceClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        return client.check().isPresent() ? Health.up().build() : Health.down().build();
    }
}
