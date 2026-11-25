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
    void lossConfirmationAndDiscrepancyResolutionHaveDistinctEventSemantics() throws IOException {
        var loss=codec.decode(fixture("valid-transfer-loss-confirmed.json"));
        assertThat(loss.eventType()).isEqualTo("TransferLossConfirmed");
        assertThat(loss.data().operation()).isEqualTo("CONFIRM_LOSS");
        assertThat(loss.data().statusAfter()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(loss.data().custodyMovements()).singleElement()
                .satisfies(movement->assertThat(movement.movementType()).isEqualTo("LOSS"));
        assertThat(codec.supportsEventType("TransferLossConfirmed")).isTrue();

        var closure=codec.decode(fixture("valid-transfer-discrepancy-resolved.json"));
        assertThat(closure.eventType()).isEqualTo("TransferDiscrepancyResolved");
        assertThat(closure.data().operation()).isEqualTo("CLOSE");
        assertThat(closure.data().statusAfter()).isEqualTo("CLOSED_WITH_DISCREPANCY");
        assertThat(closure.data().custodyMovements()).isEmpty();
        assertThat(codec.encode(closure)).contains("TransferDiscrepancyResolved");

        assertMalformed(fixture("valid-transfer-loss-confirmed.json")
                .replace("TransferLossConfirmed","TransferDiscrepancyResolved"));
        assertMalformed(fixture("valid-transfer-loss-confirmed.json")
                .replace("RECONCILIATION_REQUIRED","CLOSED_WITH_DISCREPANCY"));
        assertMalformed(fixture("valid-transfer-discrepancy-resolved.json")
                .replace("CLOSED_WITH_DISCREPANCY","PARTIALLY_RECEIVED"));
        assertMalformed(fixture("valid-transfer-discrepancy-resolved.json")
                .replace("TransferDiscrepancyResolved","TransferLossConfirmed"));
    }

    @Test
    void eventTypesMatchEverySupportedTransferCommand() throws IOException {
        String dispatched=fixture("valid-transfer-dispatched.json");
        var mappings=java.util.Map.ofEntries(
                java.util.Map.entry("APPROVE","TransferCommandRecorded"),
                java.util.Map.entry("REJECT","TransferCommandRecorded"),
                java.util.Map.entry("CANCEL","TransferCommandRecorded"),
                java.util.Map.entry("START_PICK","TransferCommandRecorded"),
                java.util.Map.entry("PICK","TransferCommandRecorded"),
                java.util.Map.entry("DISPATCH","TransferDispatched"),
                java.util.Map.entry("RECEIVE","TransferReceived"),
                java.util.Map.entry("OPEN_DISCREPANCY","TransferCommandRecorded"),
                java.util.Map.entry("CONFIRM_RETURN","TransferReturned"),
                java.util.Map.entry("REQUEST_RETURN","TransferCommandRecorded"),
                java.util.Map.entry("RESUME","TransferCommandRecorded"),
                java.util.Map.entry("RECONCILE","TransferCommandRecorded"));
        for(var mapping:mappings.entrySet()) {
            String payload=dispatched.replace("\"eventType\": \"TransferDispatched\"",
                            "\"eventType\": \""+mapping.getValue()+"\"")
                    .replace("\"operation\": \"DISPATCH\"",
                            "\"operation\": \""+mapping.getKey()+"\"");
            assertThat(codec.decode(payload).eventType()).as(mapping.getKey()).isEqualTo(mapping.getValue());
        }
        assertThat(codec.decode(fixture("valid-transfer-loss-confirmed.json")).eventType()).isEqualTo("TransferLossConfirmed");
        assertThat(codec.decode(fixture("valid-transfer-discrepancy-resolved.json")).eventType())
                .isEqualTo("TransferDiscrepancyResolved");
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
