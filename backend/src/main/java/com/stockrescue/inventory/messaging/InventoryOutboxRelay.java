package com.stockrescue.inventory.messaging;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository.OutboxEvent;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository.RedriveLease;
import com.stockrescue.transfers.messaging.TransferAggregateEvent;
import com.stockrescue.transfers.messaging.TransferEventCodec;
import com.stockrescue.transfers.messaging.TransferEventContractException;
import com.stockrescue.transfers.persistence.TransferOutboxRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class InventoryOutboxRelay {
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(11);
    private static final int MAX_IN_FLIGHT=5;
    private static final int OUTBOX_QUEUES=4;

    private final InventoryOutboxRepository outbox;
    private final InventoryOutboxRedriveRepository redrives;
    private final InventoryEventCodec codec;
    private final TransferOutboxRepository transferOutbox;
    private final TransferOutboxRedriveRepository transferRedrives;
    private final TransferEventCodec transferCodec;
    private final KafkaTemplate<String,String> kafka;
    private final InventoryMessagingMetrics metrics;
    private final String topic;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryBase;
    private final Duration retryMaximum;
    private final UUID owner=UUID.randomUUID();
    private final AtomicInteger nextQueue=new AtomicInteger();
    private final ExecutorService senders=Executors.newFixedThreadPool(MAX_IN_FLIGHT,Thread.ofVirtual().factory());
    private final AtomicBoolean dispatching=new AtomicBoolean();

    public InventoryOutboxRelay(InventoryOutboxRepository outbox,InventoryOutboxRedriveRepository redrives,
            InventoryEventCodec codec,TransferOutboxRepository transferOutbox,TransferOutboxRedriveRepository transferRedrives,
            TransferEventCodec transferCodec,
            KafkaTemplate<String,String> kafka,
            InventoryMessagingMetrics metrics,@Value("${stockrescue.inventory.messaging.topic}") String topic,
            @Value("${stockrescue.inventory.outbox-relay.claim-size:5}") int batchSize,
            @Value("${stockrescue.inventory.outbox-relay.lease-duration:30s}") Duration lease,
            @Value("${stockrescue.inventory.outbox-relay.retry-base:1s}") Duration retryBase,
            @Value("${stockrescue.inventory.outbox-relay.retry-maximum:5m}") Duration retryMaximum) {
        if(batchSize<1 || batchSize>MAX_IN_FLIGHT || lease.compareTo(ACK_TIMEOUT.plusSeconds(2))<=0
                || lease.compareTo(Duration.ofMinutes(5))>0
                || retryBase.isNegative() || retryBase.isZero() || retryMaximum.compareTo(retryBase)<0
                || retryMaximum.compareTo(Duration.ofMinutes(5))>0) {
            throw new IllegalArgumentException("Inventory outbox relay bounds are invalid");
        }
        this.outbox=outbox;this.redrives=redrives;this.codec=codec;this.transferOutbox=transferOutbox;
        this.transferRedrives=transferRedrives;
        this.transferCodec=transferCodec;this.kafka=kafka;this.metrics=metrics;this.topic=topic;
        this.batchSize=batchSize;this.lease=lease;this.retryBase=retryBase;this.retryMaximum=retryMaximum;
    }

    public void dispatchOnce() {
        if(!dispatching.compareAndSet(false,true)) return;
        try {
            int[] budgets=new int[OUTBOX_QUEUES];
            int start=Math.floorMod(nextQueue.getAndIncrement(),OUTBOX_QUEUES);
            for(int slot=0;slot<batchSize;slot++) budgets[(start+slot)%OUTBOX_QUEUES]++;
            List<RedriveLease> redriveClaims=budgets[0]==0?List.of():redrives.claimDue(owner,budgets[0],lease);
            List<com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository.RedriveLease> transferRedriveClaims=
                    budgets[1]==0?List.of():transferRedrives.claimDue(owner,budgets[1],lease);
            List<com.stockrescue.transfers.persistence.TransferOutboxRepository.OutboxEvent> transferClaims=budgets[2]==0
                    ?List.of():transferOutbox.claimDue(owner,budgets[2],lease);
            List<OutboxEvent> claims=budgets[3]==0?List.of():outbox.claimDue(owner,budgets[3],lease);
            metrics.claimed(claims.size()+redriveClaims.size());
            metrics.transferClaimed(transferClaims.size());
            metrics.transferRedriveClaimed(transferRedriveClaims.size());
            var sends=new ArrayList<java.util.concurrent.Future<?>>(claims.size()+redriveClaims.size()+transferClaims.size()+transferRedriveClaims.size());
            redriveClaims.forEach(claim->sends.add(senders.submit(()->deliverRedrive(claim))));
            claims.forEach(claim->sends.add(senders.submit(()->deliver(claim))));
            transferClaims.forEach(claim->sends.add(senders.submit(()->deliverTransfer(claim))));
            transferRedriveClaims.forEach(claim->sends.add(senders.submit(()->deliverTransferRedrive(claim))));
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

    private void deliverRedrive(RedriveLease claim) {
        final InventoryStockChangedEvent event;
        try {
            if(!claim.attempt().sourceFingerprintHex().equals(claim.source().fingerprint()))
                throw new InventoryEventContractException("OUTBOX_SOURCE_CHANGED");
            event=codec.fromOutbox(claim.event(),claim.source());
        } catch(InventoryEventContractException invalid) {
            if(redrives.block(claim,invalid.code())) metrics.sendError();
            return;
        }
        String key=event.tenantId()+":"+event.aggregateType()+":"+event.aggregateId();
        try {
            kafka.send(new ProducerRecord<>(topic,key,codec.encode(event))).get(ACK_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
        } catch(Exception brokerFailure) {
            metrics.sendError();
            redrives.scheduleRetry(claim,"BROKER_UNAVAILABLE",retryDelay(claim.attempt().attemptCount()));
            return;
        }
        if(redrives.markPublished(claim)) metrics.published();
    }

    private void deliverTransfer(com.stockrescue.transfers.persistence.TransferOutboxRepository.OutboxEvent claim) {
        final TransferAggregateEvent event;
        try { event=transferCodec.fromOutbox(claim); }
        catch(TransferEventContractException invalid) {
            if(transferOutbox.park(claim,invalid.code())) metrics.transferSendError();
            return;
        }
        try {
            kafka.send(new ProducerRecord<>(topic,event.transferId().toString(),transferCodec.encode(event)))
                    .get(ACK_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
        } catch(Exception brokerFailure) {
            metrics.transferSendError();
            transferOutbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",retryDelay(claim.attemptCount()));
            return;
        }
        if(transferOutbox.markPublished(claim)) metrics.transferPublished();
    }

    private void deliverTransferRedrive(com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository.RedriveLease claim) {
        final TransferAggregateEvent event;
        try {
            if(!claim.attempt().sourceFingerprintHex().equals(transferRedrives.sourceFingerprint(claim.event())))
                throw new TransferEventContractException("OUTBOX_SOURCE_CHANGED");
            event=transferCodec.fromOutbox(claim.event());
        } catch(TransferEventContractException invalid) {
            if(transferRedrives.block(claim,invalid.code())) metrics.transferSendError();
            return;
        }
        try {
            kafka.send(new ProducerRecord<>(topic,event.transferId().toString(),transferCodec.encode(event)))
                    .get(ACK_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
        } catch(Exception brokerFailure) {
            metrics.transferSendError();
            transferRedrives.scheduleRetry(claim,"BROKER_UNAVAILABLE",retryDelay(claim.attempt().attemptCount()));
            return;
        }
        if(transferRedrives.markPublished(claim)) metrics.transferPublished();
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
