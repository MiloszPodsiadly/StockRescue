package com.stockrescue.inventory.messaging;

import java.io.IOException;

import com.stockrescue.inventory.messaging.InventoryStockChangedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryEventContractTest {
    private final InventoryEventCodec codec=new InventoryEventCodec(new ObjectMapper());

    @Test
    void publicFixtureDecodesAndRoundTripsWithoutExpandingThePayload() throws IOException {
        String fixture=fixture("valid-stock-change.json");
        var event=codec.decode(fixture);
        assertThat(event.eventType()).isEqualTo("InventoryStockChanged");
        assertThat(event.correlationId()).isEqualTo("225c4a84-b42f-4da5-85a2-3ce5a6cb8f7e");
        assertThat(event.data().position().available()).isEqualTo(7);
        var decodedAgain=codec.decode(codec.encode(event));
        assertThat(decodedAgain).isEqualTo(event);
        assertThat(codec.encode(event)).doesNotContain("email","accessToken","supplier");
    }

    @Test
    void correlationIdsAreOpaqueNullableAndBoundedWithoutChangingUuidCompatibility() throws IOException {
        var fixture=fixture("valid-stock-change.json");
        var opaque=codec.decode(fixture("opaque-correlation-stock-change.json"));
        assertThat(opaque.correlationId()).isEqualTo("inventory-review-123");
        assertThat(codec.decode(codec.encode(opaque)).correlationId()).isEqualTo("inventory-review-123");

        for(String boundary:new String[]{"A","A".repeat(64)}) {
            var payload=withCorrelation(fixture,"\""+boundary+"\"");
            assertThat(codec.decode(payload).correlationId()).isEqualTo(boundary);
        }
        assertThat(codec.decode(fixture("tenant-spoof.json")).correlationId()).isNull();

        for(String malformed:new String[]{"\"\"","\"unsafe value\"","\""+"A".repeat(65)+"\"","7"}) {
            assertMalformed(withCorrelation(fixture,malformed));
        }
        assertMalformed(fixture.replace("\"eventId\": \"3c1c8c1d-d227-4bb7-9a5d-0b9475399232\"",
                "\"eventId\": \"not-a-uuid\""));
        assertMalformed(fixture.replace("\"causationId\": \"6b911f11-409f-4e41-bdf9-2afec2c5543c\"",
                "\"causationId\": \"not-a-uuid\""));

        var valid=codec.decode(fixture);
        var malformed=new InventoryStockChangedEvent(valid.eventId(),valid.eventType(),valid.schemaVersion(),valid.occurredAt(),
                valid.tenantId(),valid.aggregateType(),valid.aggregateId(),valid.aggregateVersion(),"unsafe value",
                valid.causationId(),valid.dataClassification(),valid.data());
        assertThatThrownBy(()->codec.encode(malformed))
                .isInstanceOfSatisfying(InventoryEventContractException.class,error->assertThat(error.code()).isEqualTo("MALFORMED_EVENT"));
    }

    @Test
    void unsupportedVersionsAndMissingCausationAreRejectedExplicitly() throws IOException {
        assertThatThrownBy(()->codec.decode(fixture("unsupported-version.json")))
                .isInstanceOfSatisfying(InventoryEventContractException.class,error->assertThat(error.code()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION"));
        assertThatThrownBy(()->codec.decode(fixture("missing-causation.json")))
                .isInstanceOfSatisfying(InventoryEventContractException.class,error->assertThat(error.code()).isEqualTo("MALFORMED_EVENT"));
        assertThatThrownBy(()->codec.decode("not-json"))
                .isInstanceOfSatisfying(InventoryEventContractException.class,error->assertThat(error.code()).isEqualTo("MALFORMED_EVENT"));
    }

    @Test
    void onlyRecognizedPermanentEventFailuresCanBeParked() {
        assertThat(new InventoryEventContractException("MALFORMED_EVENT").isPermanentContractFailure()).isTrue();
        assertThat(new InventoryEventContractException("UNSUPPORTED_SCHEMA_VERSION").isPermanentContractFailure()).isTrue();
        assertThat(new InventoryEventContractException("DATABASE_UNAVAILABLE").isPermanentContractFailure()).isFalse();
        assertThat(new InventoryEventContractException("UNRECOGNIZED_INVARIANT").isPermanentContractFailure()).isFalse();
    }

    @Test
    void tenantSpoofFixtureIsSyntacticallyValidButCannotEstablishDatabaseOwnership() throws IOException {
        var spoofed=codec.decode(fixture("tenant-spoof.json"));
        var valid=codec.decode(fixture("valid-stock-change.json"));
        assertThat(spoofed.tenantId()).isNotEqualTo(valid.tenantId());
        assertThat(spoofed.eventId()).isEqualTo(valid.eventId());
    }

    private String fixture(String name) throws IOException {
        try(var stream=getClass().getResourceAsStream("/contracts/events/v1/fixtures/"+name)) {
            if(stream==null) throw new IOException("Missing event fixture: "+name);
            return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String withCorrelation(String payload,String jsonValue) {
        return payload.replace("\"correlationId\": \"225c4a84-b42f-4da5-85a2-3ce5a6cb8f7e\"",
                "\"correlationId\": "+jsonValue);
    }

    private void assertMalformed(String payload) {
        assertThatThrownBy(()->codec.decode(payload))
                .isInstanceOfSatisfying(InventoryEventContractException.class,error->assertThat(error.code()).isEqualTo("MALFORMED_EVENT"));
    }
}
