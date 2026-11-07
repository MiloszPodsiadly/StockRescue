package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.domain.ProductCategory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FoodSafetyProductClassificationAttestationTest {
    @Test
    void productClassificationSignatureUsesItsOwnStableDomainAndExactProductRevision() {
        var organizationId=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var product=new Product(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                ProductData.parse("MILK-01","Packaged milk",null,"EA",ProductCategory.DAIRY,
                        com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass.CHILLED),
                true,7,Instant.parse("2026-11-01T00:00:00Z"),Instant.parse("2026-11-06T00:00:00Z"),null);
        var payload=FoodSafetyProductClassificationAttestation.canonicalPayload(organizationId,product,ProductCategory.DAIRY,
                "registry-issuer","ed25519-key",Instant.parse("2026-11-07T12:00:00Z"),
                Instant.parse("2026-11-07T12:00:00.123Z"),Instant.parse("2026-11-08T12:00:00.123456Z"),"evidence:classification-42");

        assertThat(new String(payload,StandardCharsets.UTF_8)).isEqualTo(String.join("\n",
                "stockrescue-product-classification-v1",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "MILK-01",
                "DAIRY",
                "7",
                "registry-issuer",
                "ed25519-key",
                "2026-11-07T12:00:00Z",
                "2026-11-07T12:00:00.123Z",
                "2026-11-08T12:00:00.123456Z",
                "evidence:classification-42"));
        assertThat(new String(payload,StandardCharsets.UTF_8)).doesNotContain("stockrescue-food-safety-approval-v2");
    }
}
