package com.stockrescue.inventory.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import com.stockrescue.transfers.messaging.TransferAggregateEvent;
import com.stockrescue.transfers.messaging.TransferEventCodec;
import com.stockrescue.transfers.persistence.TransferOutboxRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferOutboxRelayTest {
    @Test
    void publishesTransferEventsWithStableTransferKey() {
        var rig=rig();
        var records=new ArrayList<ProducerRecord<String,String>>();
        when(rig.kafka().send(org.mockito.ArgumentMatchers.<ProducerRecord<String,String>>any())).thenAnswer(invocation->{
            records.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        when(rig.transferOutbox().markPublished(rig.outboxEvent())).thenReturn(true);
        var relay=rig.relay();
        try {
            relay.dispatchOnce();
        } finally {
            relay.closeSenders();
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(rig.transferId().toString());
            assertThat(record.value()).isEqualTo("safe-transfer-event");
        });
        verify(rig.transferOutbox()).markPublished(rig.outboxEvent());
    }

    @Test
    void unavailableKafkaSchedulesATransferRetryWithoutAcknowledgingTheOutbox() {
        var rig=rig();
        when(rig.kafka().send(org.mockito.ArgumentMatchers.<ProducerRecord<String,String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        var relay=rig.relay();
        try {
            relay.dispatchOnce();
        } finally {
            relay.closeSenders();
        }

        verify(rig.transferOutbox()).scheduleRetry(eq(rig.outboxEvent()),eq("BROKER_UNAVAILABLE"),any(Duration.class));
        verify(rig.transferOutbox(),never()).markPublished(any());
    }

    @Test
    void singleEventBatchesRotateAcrossInventoryAndTransferQueues() {
        var rig=rig();
        when(rig.kafka().send(org.mockito.ArgumentMatchers.<ProducerRecord<String,String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(rig.transferOutbox().markPublished(rig.outboxEvent())).thenReturn(true);
        var relay=rig.relay(1);
        try {
            for(int attempt=0;attempt<4;attempt++) relay.dispatchOnce();
        } finally {
            relay.closeSenders();
        }

        verify(rig.inventoryRedrives()).claimDue(any(UUID.class),eq(1),any(Duration.class));
        verify(rig.transferRedrives()).claimDue(any(UUID.class),eq(1),any(Duration.class));
        verify(rig.transferOutbox()).claimDue(any(UUID.class),eq(1),any(Duration.class));
        verify(rig.inventoryOutbox()).claimDue(any(UUID.class),eq(1),any(Duration.class));
        verify(rig.transferOutbox()).markPublished(rig.outboxEvent());
    }

    @Test
    void unknownKafkaAcknowledgementRetriesTheSameTransferFact() {
        var rig=rig();
        var records=new ArrayList<ProducerRecord<String,String>>();
        when(rig.kafka().send(org.mockito.ArgumentMatchers.<ProducerRecord<String,String>>any())).thenAnswer(invocation->{
            records.add(invocation.getArgument(0));
            return records.size()==1
                    ?CompletableFuture.failedFuture(new TimeoutException("broker acknowledgement was uncertain"))
                    :CompletableFuture.completedFuture(null);
        });
        when(rig.transferOutbox().markPublished(rig.outboxEvent())).thenReturn(true);
        var relay=rig.relay();
        try {
            relay.dispatchOnce();
            relay.dispatchOnce();
        } finally {
            relay.closeSenders();
        }

        assertThat(records).hasSize(2);
        assertThat(records).extracting(ProducerRecord::key).containsOnly(rig.transferId().toString());
        assertThat(records).extracting(ProducerRecord::value).containsOnly("safe-transfer-event");
        verify(rig.transferOutbox()).scheduleRetry(eq(rig.outboxEvent()),eq("BROKER_UNAVAILABLE"),any(Duration.class));
        verify(rig.transferOutbox()).markPublished(rig.outboxEvent());
    }

    private static Rig rig() {
        var inventoryOutbox=mock(InventoryOutboxRepository.class);
        var inventoryRedrives=mock(InventoryOutboxRedriveRepository.class);
        var transferOutbox=mock(TransferOutboxRepository.class);
        var transferRedrives=mock(TransferOutboxRedriveRepository.class);
        var inventoryCodec=mock(InventoryEventCodec.class);
        var transferCodec=mock(TransferEventCodec.class);
        @SuppressWarnings("unchecked")
        var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        var metrics=mock(InventoryMessagingMetrics.class);
        when(inventoryRedrives.claimDue(any(UUID.class),anyInt(),any(Duration.class))).thenReturn(List.of());
        when(transferRedrives.claimDue(any(UUID.class),anyInt(),any(Duration.class))).thenReturn(List.of());
        when(inventoryOutbox.claimDue(any(UUID.class),anyInt(),any(Duration.class))).thenReturn(List.of());
        UUID organization=UUID.randomUUID(),transferId=UUID.randomUUID(),eventId=UUID.randomUUID(),commandId=UUID.randomUUID();
        UUID source=UUID.randomUUID(),destination=UUID.randomUUID(),owner=UUID.randomUUID();
        var outboxEvent=new TransferOutboxRepository.OutboxEvent(eventId,organization,transferId,0,
                "TransferCommandRecorded",1,"{}",null,Instant.parse("2026-11-19T12:00:00Z"),owner,1,1,
                Instant.now().plusSeconds(30));
        var event=new TransferAggregateEvent(eventId,"TransferCommandRecorded",1,outboxEvent.occurredAt(),organization,
                transferId,0,null,commandId,commandId,"INTERNAL",new TransferAggregateEvent.Data("CREATE",null,
                        "REQUESTED",source,destination,List.of(),List.of()));
        when(transferOutbox.claimDue(any(UUID.class),anyInt(),any(Duration.class))).thenReturn(List.of(outboxEvent));
        when(transferCodec.fromOutbox(outboxEvent)).thenReturn(event);
        when(transferCodec.encode(event)).thenReturn("safe-transfer-event");
        return new Rig(inventoryOutbox,inventoryRedrives,transferOutbox,transferRedrives,inventoryCodec,
                transferCodec,kafka,metrics,transferId,outboxEvent);
    }

    private record Rig(InventoryOutboxRepository inventoryOutbox,InventoryOutboxRedriveRepository inventoryRedrives,
            TransferOutboxRepository transferOutbox,TransferOutboxRedriveRepository transferRedrives,
            InventoryEventCodec inventoryCodec,TransferEventCodec transferCodec,KafkaTemplate<String,String> kafka,
            InventoryMessagingMetrics metrics,UUID transferId,TransferOutboxRepository.OutboxEvent outboxEvent) {
        private InventoryOutboxRelay relay() {
            return relay(5);
        }
        private InventoryOutboxRelay relay(int batchSize) {
            return new InventoryOutboxRelay(inventoryOutbox,inventoryRedrives,inventoryCodec,transferOutbox,
                    transferRedrives,transferCodec,kafka,metrics,"stockrescue.inventory.events.v1",batchSize,
                    Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofMinutes(2));
        }
    }
}
