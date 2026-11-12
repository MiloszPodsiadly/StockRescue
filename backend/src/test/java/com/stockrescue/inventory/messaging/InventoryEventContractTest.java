package com.stockrescue.inventory.messaging;

import java.io.IOException;

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
        assertThat(event.data().position().available()).isEqualTo(7);
        var decodedAgain=codec.decode(codec.encode(event));
        assertThat(decodedAgain).isEqualTo(event);
        assertThat(codec.encode(event)).doesNotContain("email","accessToken","supplier");
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
}
