package com.stockrescue.inventory.messaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository.OutboxEvent;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class InventoryOutboxRelay {
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(11);
    private static final int MAX_IN_FLIGHT=5;

    private final InventoryOutboxRepository outbox;
    private final InventoryEventCodec codec;
    private final KafkaTemplate<String,String> kafka;
    private final InventoryMessagingMetrics metrics;
    private final String topic;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryBase;
    private final Duration retryMaximum;
    private final UUID owner=UUID.randomUUID();
    private final ExecutorService senders=Executors.newFixedThreadPool(MAX_IN_FLIGHT,Thread.ofVirtual().factory());
    private final AtomicBoolean dispatching=new AtomicBoolean();

    public InventoryOutboxRelay(InventoryOutboxRepository outbox,InventoryEventCodec codec,KafkaTemplate<String,String> kafka,
            InventoryMessagingMetrics metrics,@Value("${stockrescue.inventory.messaging.topic}") String topic,
            @Value("${stockrescue.inventory.outbox-relay.claim-size:5}") int batchSize,
            @Value("${stockrescue.inventory.outbox-relay.lease-duration:30s}") Duration lease,
            @Value("${stockrescue.inventory.outbox-relay.retry-base:1s}") Duration retryBase,
            @Value("${stockrescue.inventory.outbox-relay.retry-maximum:5m}") Duration retryMaximum) {
        if(batchSize<1 || batchSize>MAX_IN_FLIGHT || lease.compareTo(ACK_TIMEOUT.plusSeconds(2))<=0
                || lease.compareTo(Duration.ofMinutes(5))>0
                || retryBase.isNegative() || retryBase.isZero() || retryMaximum.compareTo(retryBase)<0) {
            throw new IllegalArgumentException("Inventory outbox relay bounds are invalid");
        }
        this.outbox=outbox;this.codec=codec;this.kafka=kafka;this.metrics=metrics;this.topic=topic;
        this.batchSize=batchSize;this.lease=lease;this.retryBase=retryBase;this.retryMaximum=retryMaximum;
    }

    public void dispatchOnce() {
        if(!dispatching.compareAndSet(false,true)) return;
        try {
            List<OutboxEvent> claims=outbox.claimDue(owner,batchSize,lease);
            metrics.claimed(claims.size());
            var sends=claims.stream().map(claim->senders.submit(()->deliver(claim))).toList();
            for(var send:sends) {
                try { send.get(ACK_TIMEOUT.plusSeconds(2).toMillis(),TimeUnit.MILLISECONDS); }
                catch(Exception interruptedOrFailed) {
                    if(interruptedOrFailed instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
        } finally { dispatching.set(false); }
    }

    private void deliver(OutboxEvent claim) {
        final InventoryStockChangedEvent event;
        try { event=codec.fromOutbox(claim); }
        catch(InventoryEventContractException invalid) {
            if(outbox.park(claim,invalid.code())) metrics.sendError();
            return;
        }
        String key=event.tenantId()+":"+event.aggregateType()+":"+event.aggregateId();
        try {
            kafka.send(new ProducerRecord<>(topic,key,codec.encode(event))).get(ACK_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
        } catch(Exception brokerFailure) {
            metrics.sendError();
            outbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",retryDelay(claim.attemptCount()));
            return;
        }
        if(outbox.markPublished(claim)) metrics.published();
    }

    private Duration retryDelay(int attempts) {
        long base=retryBase.toMillis(),maximum=retryMaximum.toMillis();
        long exponent=Math.min(30,Math.max(0,attempts-1));
        long multiplier=1L<<Math.min(exponent,30);
        long capped=base>maximum/multiplier?maximum:Math.min(maximum,base*multiplier);
        double jitter=ThreadLocalRandom.current().nextDouble(0.75,1.251);
        return Duration.ofMillis(Math.max(1,Math.min(maximum,(long)(capped*jitter))));
    }

    @PreDestroy
    void closeSenders() { senders.close(); }
}
