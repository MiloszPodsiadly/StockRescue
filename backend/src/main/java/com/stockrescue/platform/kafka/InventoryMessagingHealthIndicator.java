package com.stockrescue.platform.kafka;

import com.stockrescue.inventory.messaging.InventoryMessagingMetrics;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("inventoryMessaging")
public class InventoryMessagingHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry listeners;
    private final InventoryMessagingMetrics metrics;

    public InventoryMessagingHealthIndicator(KafkaListenerEndpointRegistry listeners,InventoryMessagingMetrics metrics) {
        this.listeners=listeners;this.metrics=metrics;
    }

    @Override
    public Health health() {
        var listener=listeners.getListenerContainer("inventory-change-projection");
        if(listener==null || !listener.isRunning()) return Health.down().withDetail("state","stopped").build();
        long retrying=metrics.retryingRecordCount();
        if(retrying>0) return Health.down().withDetail("state","retrying").withDetail("retryingRecords",retrying)
                .withDetail("retryAgeSeconds",metrics.oldestRetryAgeSeconds()).build();
        return Health.up().withDetail("state","running").build();
    }
}
