package com.stockrescue.inventory.messaging;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("inventoryMessaging")
public class InventoryMessagingHealthIndicator implements HealthIndicator {
    private static final String LISTENER_ID = "inventory-change-projection";

    private final KafkaListenerEndpointRegistry listeners;
    private final InventoryMessagingMetrics metrics;
    private final InventoryMessagingStartup startup;

    public InventoryMessagingHealthIndicator(KafkaListenerEndpointRegistry listeners,
            InventoryMessagingMetrics metrics, InventoryMessagingStartup startup) {
        this.listeners = listeners;
        this.metrics = metrics;
        this.startup = startup;
    }

    @Override
    public Health health() {
        var listener=listeners.getListenerContainer(LISTENER_ID);
        boolean running=startup.listenerRunning();
        long retrying = metrics.retryingRecordCount();
        if (retrying > 0) {
            String state=metrics.blockedPartitionCount()>0?"blocked":"retrying";
            return Health.down().withDetail("state", state)
                    .withDetail("retryingRecords", retrying)
                    .withDetail("retryAgeSeconds", metrics.oldestRetryAgeSeconds())
                    .withDetail("blockedPartitions",metrics.blockedPartitionCount())
                    .withDetail("pausedPartitions",metrics.pausedPartitionCount())
                    .withDetail("failedRecords",metrics.failedRecords())
                    .withDetail("listenerRunning",running)
                    .withDetail("listenerState",startup.state().name()).build();
        }

        return switch (startup.state()) {
            case STARTING -> Health.status(new Status("STARTING"))
                    .withDetail("state", "starting").withDetail("listenerRunning",running).build();
            case WAITING_FOR_KAFKA -> Health.down()
                    .withDetail("state", "waiting_for_kafka").withDetail("listenerRunning",running).build();
            case RUNNING -> running && startup.brokerAvailable()
                    ?Health.up().withDetail("state", "running").withDetail("restartAttempts",startup.restartAttempts()).build()
                    :Health.down().withDetail("state", "retrying")
                            .withDetail("reason", listener==null?"listener_missing":!running?"listener_stopped":"broker_unavailable")
                            .withDetail("listenerRunning",running).build();
            case RETRYING -> Health.down().withDetail("state", "retrying")
                    .withDetail("brokerAvailable",startup.brokerAvailable())
                    .withDetail("restartAttempts",startup.restartAttempts())
                    .withDetail("listenerRunning",running)
                    .withDetail("lastFailureType",startup.lastFailureType()==null?"none":startup.lastFailureType()).build();
            case STOPPED_UNEXPECTEDLY -> Health.down().withDetail("state", "stopped_unexpectedly")
                    .withDetail("restartAttempts",startup.restartAttempts())
                    .withDetail("restartMaximumAttempts",startup.restartMaximumAttempts())
                    .withDetail("alert",startup.restartAttempts()>=startup.restartMaximumAttempts())
                    .withDetail("listenerRunning",running)
                    .withDetail("lastFailureType",startup.lastFailureType()==null?"unknown":startup.lastFailureType()).build();
            case STOPPING -> Health.down().withDetail("state", "stopping").build();
            case HALTED -> Health.down().withDetail("state", "halted").build();
        };
    }
}
