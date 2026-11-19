package com.stockrescue.transfers.messaging;

import java.io.IOException;

import com.stockrescue.transfers.persistence.TransferOutboxRepository.OutboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferEventContractTest {
    private final TransferEventCodec codec=new TransferEventCodec(new ObjectMapper());

    @Test
    void publicDispatchAndReceiptFixturesDecodeAsVersionedMinimalContracts() throws IOException {
        var dispatched=codec.decode(fixture("valid-transfer-dispatched.json"));
        assertThat(dispatched.eventType()).isEqualTo("TransferDispatched");
        assertThat(dispatched.data().custodyMovements()).hasSize(1);
        assertThat(dispatched.data().stockMovements()).hasSize(1);
        var received=codec.decode(fixture("valid-transfer-received.json"));
        assertThat(received.eventType()).isEqualTo("TransferReceived");
        assertThat(received.correlationId()).isNull();
        assertThat(codec.decode(codec.encode(received))).isEqualTo(received);
        assertThat(codec.encode(dispatched)).doesNotContain("sku","lotCode","reason","evidenceReference","email","token");
    }

    @Test
    void rejectsUnknownFieldsVersionAndCausationMismatch() throws IOException {
        String fixture=fixture("valid-transfer-dispatched.json");
        assertMalformed(fixture.replace("\"schemaVersion\": 1","\"schemaVersion\": 2"));
        assertMalformed(fixture.replace("\"commandId\": \"8c0cd005-2047-495c-9b18-d7db7401c6cc\"",
                "\"commandId\": \"a16d644d-3d68-43c0-9a61-ff17057c5d96\""));
        assertMalformed(fixture.replace("\"dataClassification\": \"INTERNAL\"",
                "\"dataClassification\": \"INTERNAL\", \"operatorEmail\": \"private@example.test\""));
        assertMalformed(fixture.replace("\"operation\": \"DISPATCH\"", "\"operation\": \"EXPORT\""));
        assertMalformed(fixture.replace("\"statusAfter\": \"IN_TRANSIT\"", "\"statusAfter\": \"UNKNOWN\""));
        assertMalformed("not-json");
    }

    @Test
    void rejectsOutboxRowWhoseAggregateVersionDiffersFromItsImmutablePayload() throws IOException {
        String payload=fixture("valid-transfer-dispatched.json");
        var event=codec.decode(payload);
        var row=new OutboxEvent(event.eventId(),event.orgId(),event.transferId(),event.aggregateVersion()+1,
                event.eventType(),event.schemaVersion(),payload,event.correlationId(),event.occurredAt(),null,0,0,null);
        assertThatThrownBy(()->codec.fromOutbox(row)).isInstanceOf(TransferEventContractException.class);
    }

    private String fixture(String name) throws IOException {
        try(var stream=getClass().getResourceAsStream("/contracts/events/v1/fixtures/"+name)) {
            if(stream==null) throw new IOException("Missing transfer event fixture: "+name);
            return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void assertMalformed(String payload) {
        assertThatThrownBy(()->codec.decode(payload)).isInstanceOf(TransferEventContractException.class);
    }
}
