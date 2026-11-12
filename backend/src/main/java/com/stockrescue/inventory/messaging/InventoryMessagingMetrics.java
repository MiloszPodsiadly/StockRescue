package com.stockrescue.inventory.messaging;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InventoryMessagingMetrics {
    private final Counter attempted;
    private final Counter published;
    private final Counter sendErrors;
    private final Counter applied;
    private final Counter stale;
    private final Counter duplicates;
    private final Counter consumerParked;

    public InventoryMessagingMetrics(MeterRegistry registry, InventoryOutboxRepository outbox,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumerName) {
        attempted=Counter.builder("stockrescue.inventory.outbox.claims").register(registry);
        published=Counter.builder("stockrescue.inventory.outbox.published").register(registry);
        sendErrors=Counter.builder("stockrescue.inventory.outbox.errors").register(registry);
        applied=Counter.builder("stockrescue.inventory.consumer.applied").register(registry);
        stale=Counter.builder("stockrescue.inventory.consumer.stale").register(registry);
        duplicates=Counter.builder("stockrescue.inventory.consumer.duplicates").register(registry);
        consumerParked=Counter.builder("stockrescue.inventory.consumer.parked").register(registry);
        registry.gauge("stockrescue.inventory.outbox.pending",outbox,InventoryOutboxRepository::pendingCount);
        registry.gauge("stockrescue.inventory.outbox.oldest.pending.seconds",outbox,InventoryOutboxRepository::oldestPendingSeconds);
        registry.gauge("stockrescue.inventory.outbox.parked",outbox,InventoryOutboxRepository::parkedCount);
        registry.gauge("stockrescue.inventory.outbox.attempts.total",outbox,InventoryOutboxRepository::totalAttempts);
        registry.gauge("stockrescue.inventory.outbox.deliveries.with.errors",outbox,InventoryOutboxRepository::deliveriesWithErrors);
        registry.gauge("stockrescue.inventory.projection.staleness.seconds",outbox,
                repository->repository.projectionStalenessSeconds(consumerName));
    }

    public void claimed(long count) { attempted.increment(count); }
    public void published() { published.increment(); }
    public void sendError() { sendErrors.increment(); }
    public void applied() { applied.increment(); }
    public void stale() { stale.increment(); }
    public void duplicate() { duplicates.increment(); }
    public void consumerParked() { consumerParked.increment(); }
    public double duplicateCount() { return duplicates.count(); }
}
