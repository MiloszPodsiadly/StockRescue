package com.stockrescue.platform.kafka;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("inventoryMessaging")
public class InventoryMessagingHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry listeners;

    public InventoryMessagingHealthIndicator(KafkaListenerEndpointRegistry listeners) { this.listeners=listeners; }

    @Override
    public Health health() {
        var listener=listeners.getListenerContainer("inventory-change-projection");
        return listener!=null && listener.isRunning()?Health.up().build():Health.down().build();
    }
}
