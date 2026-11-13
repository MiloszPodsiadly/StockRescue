package com.stockrescue.inventory.messaging;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InventoryMessagingMetrics {
    private final Counter attempted;
    private final Counter published;
    private final Counter sendErrors;
    private final Counter applied;
    private final Counter stale;
    private final Counter duplicates;
    private final Counter consumerParked;
    private final Counter consumerRetries;
    private final Counter consumerRetryRecoveryFailures;
    private final ConcurrentMap<ConsumerRecordPosition,Long> retryingRecords=new ConcurrentHashMap<>();

    public InventoryMessagingMetrics(MeterRegistry registry, InventoryOutboxRepository outbox,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumerName) {
        attempted=Counter.builder("stockrescue.inventory.outbox.claims").register(registry);
        published=Counter.builder("stockrescue.inventory.outbox.published").register(registry);
        sendErrors=Counter.builder("stockrescue.inventory.outbox.errors").register(registry);
        applied=Counter.builder("stockrescue.inventory.consumer.applied").register(registry);
        stale=Counter.builder("stockrescue.inventory.consumer.stale").register(registry);
        duplicates=Counter.builder("stockrescue.inventory.consumer.duplicates").register(registry);
        consumerParked=Counter.builder("stockrescue.inventory.consumer.parked").register(registry);
        consumerRetries=Counter.builder("stockrescue.inventory.consumer.retry.attempts").register(registry);
        consumerRetryRecoveryFailures=Counter.builder("stockrescue.inventory.consumer.retry.recovery.failures").register(registry);
        Gauge.builder("stockrescue.inventory.consumer.retrying.records",this,InventoryMessagingMetrics::retryingRecordCount).register(registry);
        Gauge.builder("stockrescue.inventory.consumer.retry.oldest.seconds",this,InventoryMessagingMetrics::oldestRetryAgeSeconds).register(registry);
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
    public void consumerRetrying(ConsumerRecord<?,?> record) {
        consumerRetries.increment();
        retryingRecords.putIfAbsent(position(record),System.nanoTime());
    }
    public void consumerRetryRecovered(ConsumerRecord<?,?> record) { retryingRecords.remove(position(record)); }
    public void consumerRetryRecoveryFailed(ConsumerRecord<?,?> record) {
        consumerRetries.increment();
        consumerRetryRecoveryFailures.increment();
        retryingRecords.putIfAbsent(position(record),System.nanoTime());
    }
    public long retryingRecordCount() { return retryingRecords.size(); }
    public double consumerRetryCount() { return consumerRetries.count(); }
    public double oldestRetryAgeSeconds() {
        long now=System.nanoTime();
        return retryingRecords.values().stream().mapToLong(started->now-started).max().orElse(0L)/1_000_000_000d;
    }
    public double duplicateCount() { return duplicates.count(); }

    private static ConsumerRecordPosition position(ConsumerRecord<?,?> record) {
        return new ConsumerRecordPosition(record.topic(),record.partition(),record.offset());
    }

    private record ConsumerRecordPosition(String topic,int partition,long offset) { }
}
