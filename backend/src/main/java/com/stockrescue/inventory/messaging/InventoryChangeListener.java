package com.stockrescue.inventory.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import com.stockrescue.inventory.application.InventoryChangeProcessor;
import com.stockrescue.inventory.persistence.InventoryParkedEventRepository;
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
    private final InventoryMessagingMetrics metrics;
    private final String consumer;

    public InventoryChangeListener(InventoryChangeProcessor processor,InventoryParkedEventRepository parked,
            InventoryEventCodec codec,InventoryMessagingMetrics metrics,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumer) {
        this.processor=processor;this.parked=parked;this.codec=codec;this.metrics=metrics;this.consumer=consumer;
    }

    @KafkaListener(id="inventory-change-projection",topics="${stockrescue.inventory.messaging.topic}",
            groupId="${spring.kafka.consumer.group-id}",containerFactory="inventoryKafkaListenerContainerFactory",
            autoStartup="false")
    public void consume(ConsumerRecord<String,String> record,Acknowledgment acknowledgment) {
        try {
            var outcome=processor.process(record.key(),record.value());
            switch(outcome) {
                case APPLIED -> metrics.applied();
                case STALE -> metrics.stale();
                case DUPLICATE -> metrics.duplicate();
            }
        } catch(InventoryEventContractException invalid) {
            if(!invalid.isPermanentContractFailure()) throw invalid;
            parked.record(consumer,record.topic(),record.partition(),record.offset(),codec.eventIdOrNull(record.value()),
                    digest(record.value()),invalid.code());
            metrics.consumerParked();
        }
        acknowledgment.acknowledge();
        metrics.consumerRetryRecovered(record);
    }

    private static byte[] digest(String payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
