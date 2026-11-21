package com.stockrescue.transfers.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.stockrescue.transfers.persistence.TransferInboxRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransferInboxProcessor {
    private final TransferEventCodec codec;
    private final TransferOutboxRepository outbox;
    private final TransferInboxRepository inbox;
    private final String consumer;

    public TransferInboxProcessor(TransferEventCodec codec,TransferOutboxRepository outbox,TransferInboxRepository inbox,
            @Value("${stockrescue.transfer.messaging.consumer-name:transfer-change-projection-v1}") String consumer) {
        this.codec=codec;this.outbox=outbox;this.inbox=inbox;this.consumer=consumer;
    }

    public TransferInboxRepository.Outcome process(String payload) {
        return process(payload,consumer);
    }

    @Transactional
    public TransferInboxRepository.Outcome process(String payload,String consumerName) {
        var event=codec.decode(payload);
        var stored=outbox.find(event.eventId());
        if(stored==null) throw new TransferEventContractException("UNKNOWN_EVENT");
        var source=codec.fromOutbox(stored);
        if(!event.equals(source)) throw new TransferEventContractException("EVENT_SOURCE_MISMATCH");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return inbox.receive(consumerName,event,payload,digest);
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public String eventType(String payload) { return codec.eventType(payload); }
    public boolean supportsEventType(String eventType) { return codec.supportsEventType(eventType); }
}
