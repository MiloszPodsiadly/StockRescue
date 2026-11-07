package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FoodSafetyPolicyAttestationTest {
    @Test
    void currentRulesetManifestHasAnExplicitVersionedDigest() {
        var ruleset=new FoodSafetyRuleset();
        assertThat(ruleset.id()).isEqualTo("food-safety-conservative-pilot-v1");
        assertThat(ruleset.digestHex()).isEqualTo("17e0dcd0f82f7f469e2a76b7d18f5279f8c64c9b0f37456a7550ce764ac6fd97");
    }

    @Test
    void signedTimestampsMustFitTheDatabaseYearRangeAndMicrosecondPrecision() {
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.parse("0001-01-01T00:00:00Z"))).isTrue();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.parse("9999-12-31T23:59:59.999999Z"))).isTrue();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.parse("0000-12-31T23:59:59Z"))).isFalse();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.parse("+10000-01-01T00:00:00Z"))).isFalse();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.parse("2026-11-07T12:00:00.123456001Z"))).isFalse();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.MIN)).isFalse();
        assertThat(CanonicalAttestationTimestamp.isDatabaseRepresentable(Instant.MAX)).isFalse();
    }

    @Test
    void canonicalPayloadHasStableUtf8OrderingAndTimestampPrecision() {
        var organizationId=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var policy=new FoodSafetyPolicyVersion(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),ProductCategory.BAKERY,TemperatureClass.AMBIENT,2,
                FoodSafetyPolicyVersion.PartialDateRule.NO_NORMALIZATION,FoodSafetyPolicyVersion.ApprovalStatus.UNVERIFIED,
                new EvidenceProvenance("Reviewed policy", "policy:ambient-v2"),UUID.fromString("00000000-0000-0000-0000-000000000004"),
                Instant.parse("2026-11-07T11:00:00Z"),"Reviewed policy evidence");
        var ruleset=new FoodSafetyRuleset();
        var payload=FoodSafetyPolicyAttestation.canonicalPayload(organizationId,policy,
                HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                ruleset.id(),ruleset.digest(),Set.of(Operation.TRANSFER,Operation.SALE),"registry-issuer","ed25519-key",
                Instant.parse("2026-11-07T12:00:00Z"),Instant.parse("2026-11-07T12:00:00.123Z"),
                Instant.parse("2026-11-08T12:00:00.123456Z"),"evidence:case-42");

        assertThat(new String(payload,StandardCharsets.UTF_8)).isEqualTo(String.join("\n",
                "stockrescue-food-safety-approval-v2",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003",
                "2",
                "BAKERY",
                "AMBIENT",
                "SALE,TRANSFER",
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                "food-safety-conservative-pilot-v1",
                "17e0dcd0f82f7f469e2a76b7d18f5279f8c64c9b0f37456a7550ce764ac6fd97",
                "registry-issuer",
                "ed25519-key",
                "2026-11-07T12:00:00Z",
                "2026-11-07T12:00:00.123Z",
                "2026-11-08T12:00:00.123456Z",
                "evidence:case-42"));
    }
}
