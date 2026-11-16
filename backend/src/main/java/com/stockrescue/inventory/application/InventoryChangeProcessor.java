package com.stockrescue.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.stockrescue.inventory.messaging.InventoryEventCodec;
import com.stockrescue.inventory.messaging.InventoryEventContractException;
import com.stockrescue.inventory.messaging.InventoryStockChangedEvent;
import com.stockrescue.inventory.persistence.InventoryMessagingRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryChangeProcessor {
    public enum Outcome { APPLIED, STALE, DUPLICATE }

    private final InventoryEventCodec codec;
    private final InventoryOutboxRepository outbox;
    private final InventoryMessagingRepository repository;
    private final ObjectMapper json;
    private final String consumer;

    public InventoryChangeProcessor(InventoryEventCodec codec,InventoryOutboxRepository outbox,
            InventoryMessagingRepository repository,ObjectMapper json,
            @Value("${stockrescue.inventory.messaging.consumer-name}") String consumer) {
        this.codec=codec;this.outbox=outbox;this.repository=repository;this.json=json;this.consumer=consumer;
    }

    @Transactional
    public Outcome process(String key,String payload) {
        InventoryStockChangedEvent event=codec.decode(payload);
        String expectedKey=event.tenantId()+":"+event.aggregateType()+":"+event.aggregateId();
        if(!expectedKey.equals(key)) throw failure("TENANT_OR_AGGREGATE_MISMATCH");
        var source=outbox.find(event.eventId());
        if(source==null) throw failure("UNKNOWN_EVENT");
        if(!codec.fromOutbox(source).equals(event)) throw failure("OUTBOX_EVENT_MISMATCH");
        byte[] digest=digest(payload);
        var existing=repository.findInbox(consumer,event.eventId());
        if(existing!=null) {
            if(!Arrays.equals(existing.payloadDigest(),digest)) throw failure("DUPLICATE_PAYLOAD_MISMATCH");
            return Outcome.DUPLICATE;
        }
        Long currentVersion=repository.lockProjectionVersion(consumer,event);
        boolean isStale=currentVersion!=null && currentVersion>=event.aggregateVersion();
        String outcome=isStale?"STALE":"APPLIED";
        String changeJson=json.writeValueAsString(event.data());
        repository.insertInbox(consumer,event,digest,outcome);
        repository.appendFeed(consumer,event,changeJson);
        if(!isStale) repository.upsertProjection(consumer,event,changeJson);
        return isStale?Outcome.STALE:Outcome.APPLIED;
    }

    private static byte[] digest(String payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static InventoryEventContractException failure(String code) { return new InventoryEventContractException(code); }
}
