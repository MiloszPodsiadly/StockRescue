package com.stockrescue.inventory.messaging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConcurrentContainerStoppedEvent;
import org.springframework.kafka.event.ConsumerPausedEvent;
import org.springframework.kafka.event.ConsumerPartitionPausedEvent;
import org.springframework.kafka.event.ConsumerPartitionResumedEvent;
import org.springframework.kafka.event.ConsumerResumedEvent;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class InventoryMessagingMetrics {
    private final MeterRegistry registry;
    private final Counter attempted;
    private final Counter published;
    private final Counter sendErrors;
    private final Counter applied;
    private final Counter stale;
    private final Counter duplicates;
    private final Counter consumerParked;
    private final Counter consumerRetries;
    private final Counter consumerRetryRecoveryFailures;
    private final Counter consumerListenerRestartAttempts;
    private final ConcurrentMap<InventoryConsumerFailure.Category,Counter> failuresByCategory=new ConcurrentHashMap<>();
    private final ConcurrentMap<TopicPartition,FailureState> activeFailures=new ConcurrentHashMap<>();
    private final ConcurrentMap<TopicPartition,List<Meter>> failureGauges=new ConcurrentHashMap<>();
    private final java.util.Set<TopicPartition> pausedPartitions=ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<TopicPartition,AtomicLong> pausedValues=new ConcurrentHashMap<>();
    private final ConcurrentMap<TopicPartition,Meter> pausedGauges=new ConcurrentHashMap<>();
    private final AtomicLong lastSuccessfulProcessingEpochSeconds=new AtomicLong();

    public InventoryMessagingMetrics(MeterRegistry registry,InventoryOutboxRepository outbox,
            InventoryOutboxRedriveRepository redrives,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumerName) {
        this.registry=registry;
        attempted=Counter.builder("stockrescue.inventory.outbox.claims").register(registry);
        published=Counter.builder("stockrescue.inventory.outbox.published").register(registry);
        sendErrors=Counter.builder("stockrescue.inventory.outbox.errors").register(registry);
        applied=Counter.builder("stockrescue.inventory.consumer.applied").register(registry);
        stale=Counter.builder("stockrescue.inventory.consumer.stale").register(registry);
        duplicates=Counter.builder("stockrescue.inventory.consumer.duplicates").register(registry);
        consumerParked=Counter.builder("stockrescue.inventory.consumer.parked").register(registry);
        consumerRetries=Counter.builder("stockrescue.inventory.consumer.retry.attempts").register(registry);
        consumerRetryRecoveryFailures=Counter.builder("stockrescue.inventory.consumer.retry.recovery.failures").register(registry);
        consumerListenerRestartAttempts=Counter.builder("stockrescue.inventory.consumer.listener.restart.attempts").register(registry);
        for(var category:InventoryConsumerFailure.Category.values()) {
            failuresByCategory.put(category,Counter.builder("stockrescue.inventory.consumer.failures")
                    .tag("category",category.name()).register(registry));
        }
        Gauge.builder("stockrescue.inventory.consumer.retrying.records",this,InventoryMessagingMetrics::retryingRecordCount)
                .register(registry);
        Gauge.builder("stockrescue.inventory.consumer.retry.oldest.seconds",this,InventoryMessagingMetrics::oldestRetryAgeSeconds)
                .register(registry);
        Gauge.builder("stockrescue.inventory.consumer.blocked.partitions",this,InventoryMessagingMetrics::blockedPartitionCount)
                .register(registry);
        Gauge.builder("stockrescue.inventory.consumer.paused.partitions",this,InventoryMessagingMetrics::pausedPartitionCount)
                .register(registry);
        Gauge.builder("stockrescue.inventory.consumer.last.success.epoch.seconds",lastSuccessfulProcessingEpochSeconds,
                AtomicLong::doubleValue).register(registry);
        registry.gauge("stockrescue.inventory.outbox.pending",outbox,InventoryOutboxRepository::pendingCount);
        registry.gauge("stockrescue.inventory.outbox.oldest.pending.seconds",outbox,InventoryOutboxRepository::oldestPendingSeconds);
        registry.gauge("stockrescue.inventory.outbox.parked",outbox,InventoryOutboxRepository::parkedCount);
        registry.gauge("stockrescue.inventory.outbox.parked.unresolved",redrives,InventoryOutboxRedriveRepository::unresolvedParkedCount);
        registry.gauge("stockrescue.inventory.outbox.parked.oldest.blocked.seconds",redrives,
                InventoryOutboxRedriveRepository::oldestUnresolvedParkedSeconds);
        registry.gauge("stockrescue.inventory.outbox.redrive.attempts.total",redrives,
                InventoryOutboxRedriveRepository::totalRedriveAttempts);
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
    public void consumerParked() {
        consumerParked.increment();
        failuresByCategory.get(InventoryConsumerFailure.Category.PERMANENT_EVENT_DATA).increment();
    }

    public void consumerRetrying(ConsumerRecord<?,?> record,InventoryConsumerFailure.Category category,int attempt) {
        consumerRetries.increment();
        failuresByCategory.get(category).increment();
        TopicPartition partition=new TopicPartition(record.topic(),record.partition());
        FailureState state=activeFailures.compute(partition,(key,current)->{
            if(current!=null) {
                current.failed(record.offset(),category,attempt);
                return current;
            }
            var created=new FailureState(record.offset(),category,attempt,System.nanoTime());
            var tags=io.micrometer.core.instrument.Tags.of("topic",record.topic(),"partition",Integer.toString(record.partition()));
            var offset=Gauge.builder("stockrescue.inventory.consumer.failed.offset",created,FailureState::offset).tags(tags).register(registry);
            var age=Gauge.builder("stockrescue.inventory.consumer.failed.age.seconds",created,FailureState::ageSeconds).tags(tags).register(registry);
            failureGauges.put(partition,List.of(offset,age));
            return created;
        });
        state.failed(record.offset(),category,attempt);
    }

    public void consumerRetryRecovered(ConsumerRecord<?,?> record) {
        removeFailure(new TopicPartition(record.topic(),record.partition()));
    }

    public void consumerRetryRecoveryFailed(ConsumerRecord<?,?> record) {
        consumerRetryRecoveryFailures.increment();
    }
    public void consumerListenerRestartAttempted() { consumerListenerRestartAttempts.increment(); }

    public void consumerRecordSucceeded() {
        lastSuccessfulProcessingEpochSeconds.set(System.currentTimeMillis()/1000);
    }

    public void consumerPartitionsRevoked(Collection<TopicPartition> partitions) {
        for(var partition:partitions) {
            removeFailure(partition);
            markResumed(partition);
        }
    }

    @EventListener
    public void consumerPaused(ConsumerPausedEvent event) { event.getPartitions().forEach(this::markPaused); }

    @EventListener
    public void consumerPartitionPaused(ConsumerPartitionPausedEvent event) { markPaused(event.getPartition()); }

    @EventListener
    public void consumerResumed(ConsumerResumedEvent event) { event.getPartitions().forEach(this::markResumed); }

    @EventListener
    public void consumerPartitionResumed(ConsumerPartitionResumedEvent event) { markResumed(event.getPartition()); }

    @EventListener
    public void consumerContainerStopped(ConcurrentContainerStoppedEvent ignored) { clearConsumerState(); }

    @PreDestroy
    public void clearConsumerState() {
        for(var partition:new ArrayList<>(activeFailures.keySet())) removeFailure(partition);
        for(var partition:new ArrayList<>(pausedPartitions)) markResumed(partition);
    }

    public long retryingRecordCount() { return activeFailures.size(); }
    public long blockedPartitionCount() {
        return activeFailures.values().stream()
                .filter(state->state.category()==InventoryConsumerFailure.Category.NONRETRYABLE_APP_UNKNOWN).count();
    }
    public long pausedPartitionCount() { return pausedPartitions.size(); }
    public double consumerRetryCount() { return consumerRetries.count(); }
    public double consumerListenerRestartAttemptCount() { return consumerListenerRestartAttempts.count(); }
    public long lastSuccessfulProcessingEpochSeconds() { return lastSuccessfulProcessingEpochSeconds.get(); }
    public double oldestRetryAgeSeconds() {
        long now=System.nanoTime();
        return activeFailures.values().stream().mapToLong(state->now-state.startedNanos()).max().orElse(0L)/1_000_000_000d;
    }
    public double duplicateCount() { return duplicates.count(); }
    public List<FailureSnapshot> failedRecords() {
        long now=System.nanoTime();
        return activeFailures.entrySet().stream().map(entry->{
            var state=entry.getValue();
            return new FailureSnapshot(entry.getKey().topic(),entry.getKey().partition(),state.offset(),
                    state.category().name(),state.attempts(),(now-state.startedNanos())/1_000_000_000d);
        }).sorted(Comparator.comparing(FailureSnapshot::topic).thenComparingInt(FailureSnapshot::partition)).toList();
    }

    private void removeFailure(TopicPartition partition) {
        activeFailures.remove(partition);
        List<Meter> gauges=failureGauges.remove(partition);
        if(gauges!=null) gauges.forEach(registry::remove);
    }

    private void markPaused(TopicPartition partition) {
        if(!pausedPartitions.add(partition)) return;
        pausedValues.computeIfAbsent(partition,key->{
            var value=new AtomicLong(1);
            Meter gauge=Gauge.builder("stockrescue.inventory.consumer.partition.paused",value,AtomicLong::doubleValue)
                    .tag("topic",key.topic()).tag("partition",Integer.toString(key.partition())).register(registry);
            pausedGauges.put(key,gauge);
            return value;
        });
    }

    private void markResumed(TopicPartition partition) {
        pausedPartitions.remove(partition);
        pausedValues.remove(partition);
        Meter gauge=pausedGauges.remove(partition);
        if(gauge!=null) registry.remove(gauge);
    }

    public record FailureSnapshot(String topic,int partition,long offset,String category,int attempts,double ageSeconds) { }

    private static final class FailureState {
        private volatile long offset;
        private volatile InventoryConsumerFailure.Category category;
        private volatile int attempts;
        private volatile long startedNanos;

        private FailureState(long offset,InventoryConsumerFailure.Category category,int attempts,long startedNanos) {
            failed(offset,category,attempts);
            this.startedNanos=startedNanos;
        }

        private synchronized void failed(long offset,InventoryConsumerFailure.Category category,int attempts) {
            this.offset=offset;
            this.category=category;
            this.attempts=Math.max(this.attempts,attempts);
        }

        private long offset() { return offset; }
        private InventoryConsumerFailure.Category category() { return category; }
        private int attempts() { return attempts; }
        private long startedNanos() { return startedNanos; }
        private double ageSeconds() { return (System.nanoTime()-startedNanos)/1_000_000_000d; }
    }
}
