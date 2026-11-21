package com.stockrescue.inventory.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import com.stockrescue.inventory.application.InventoryChangeProcessor;
import com.stockrescue.inventory.persistence.InventoryParkedEventRepository;
import com.stockrescue.transfers.messaging.TransferEventContractException;
import com.stockrescue.transfers.messaging.TransferInboxProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class InventoryChangeListener {
    private final InventoryChangeProcessor processor;
    private final InventoryParkedEventRepository parked;
    private final InventoryEventCodec codec;
    private final TransferInboxProcessor transferProcessor;
    private final InventoryMessagingMetrics metrics;
    private final String consumer;

    public InventoryChangeListener(InventoryChangeProcessor processor,InventoryParkedEventRepository parked,
            InventoryEventCodec codec,TransferInboxProcessor transferProcessor,InventoryMessagingMetrics metrics,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumer) {
        this.processor=processor;this.parked=parked;this.codec=codec;this.transferProcessor=transferProcessor;
        this.metrics=metrics;this.consumer=consumer;
    }

    @KafkaListener(id="inventory-change-projection",topics="${stockrescue.inventory.messaging.topic}",
            groupId="${spring.kafka.consumer.group-id}",containerFactory="inventoryKafkaListenerContainerFactory",
            autoStartup="false")
    public void consume(ConsumerRecord<String,String> record,Acknowledgment acknowledgment) {
        try {
            try {
                String eventType=transferProcessor.eventType(record.value());
                if(InventoryEventCodec.EVENT_TYPE.equals(eventType)) {
                    var outcome=processor.process(record.key(),record.value());
                    switch(outcome) {
                        case APPLIED -> metrics.applied();
                        case STALE -> metrics.stale();
                        case DUPLICATE -> metrics.duplicate();
                    }
                } else if(transferProcessor.supportsEventType(eventType)) {
                    var outcome=transferProcessor.process(record.value());
                    switch(outcome) {
                        case APPLIED -> metrics.transferApplied();
                        case DEFERRED -> metrics.transferDeferred();
                        case DUPLICATE -> metrics.transferDuplicate();
                    }
                } else {
                    parked.record(consumer,record.topic(),record.partition(),record.offset(),codec.eventIdOrNull(record.value()),
                            digest(record.value()),"UNSUPPORTED_EVENT_TYPE");
                    metrics.consumerParked();
                }
            } catch(InventoryEventContractException invalid) {
                if(!invalid.isPermanentContractFailure()) throw invalid;
                parked.record(consumer,record.topic(),record.partition(),record.offset(),codec.eventIdOrNull(record.value()),
                        digest(record.value()),invalid.code());
                metrics.consumerParked();
            } catch(TransferEventContractException invalid) {
                parked.record(consumer,record.topic(),record.partition(),record.offset(),codec.eventIdOrNull(record.value()),
                        digest(record.value()),invalid.code());
                metrics.consumerParked();
            }
            acknowledgment.acknowledge();
            metrics.consumerRetryRecovered(record);
            metrics.consumerRecordSucceeded();
        } catch(InventoryConsumerFailure failure) {
            throw failure;
        } catch(RuntimeException failure) {
            throw InventoryConsumerFailure.classify(failure);
        }
    }

    private static byte[] digest(String payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload==null?new byte[0]:payload.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
