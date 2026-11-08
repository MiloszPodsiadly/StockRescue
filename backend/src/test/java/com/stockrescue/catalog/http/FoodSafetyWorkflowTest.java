package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.catalog.application.FoodSafetyApprovalProperties;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.application.FoodSafetyPolicyAttestation;
import com.stockrescue.catalog.application.FoodSafetyPolicyApprovalVerifier;
import com.stockrescue.catalog.application.FoodSafetyPolicyDigest;
import com.stockrescue.catalog.application.FoodSafetyRuleset;
import com.stockrescue.catalog.application.FoodSafetyPolicyApproval;
import com.stockrescue.catalog.application.FoodSafetyPolicyApprovalVerification;
import com.stockrescue.catalog.application.FoodSafetySignatureVerifier;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationAttestation;
import com.stockrescue.catalog.application.FoodSafetyEvaluation;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyApprovalRepository;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyRepository;
import com.stockrescue.catalog.persistence.FoodSafetyProductClassificationRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoodSafetyWorkflowTest extends InfrastructureTestBase {
    private static final String PASSWORD="food-safety-test-passphrase";
    private static final Instant NOW=Instant.parse("2026-11-05T22:30:00Z");
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @Autowired private FoodSafetyApprovalProperties approvalProperties;
    @Autowired private FoodSafetyPolicyRepository policyRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private FoodSafetyPolicyApprovalRepository approvalRepository;
    @MockitoSpyBean private FoodSafetyProductClassificationRepository classifications;
    @Autowired private FoodSafetyRuleset ruleset;
    @Autowired private FoodSafetySignatureVerifier signatures;
    @MockitoBean private Clock clock;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() throws Exception {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        approvalProperties.setEnabled(false);approvalProperties.setTrustedIssuers(Map.of());f=new Fixture();
    }

    @Test
    void optionalSignedApprovalIsVerifiedWithoutControllingThePlatformAssessment() throws Exception {
        var owner=f.owner.browser();var lotId=register("2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);
        var policyResponse=owner.write(policies(),review(0,"NO_NORMALIZATION"));assertThat(policyResponse.statusCode()).isEqualTo(201);
        var policyId=UUID.fromString(tree(policyResponse).path("id").asString());
        var signer=signer();var trusted=trust(signer);

        var categoryOnly=owner.write(classificationPath(f.product),signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,
                "external-case:bread-category-v0"));
        assertThat(categoryOnly.statusCode()).as(categoryOnly.body()).isEqualTo(201);
        assertThat(tree(categoryOnly).path("outcome").asString()).isEqualTo("VERIFIED");
        var beforeOperatorConfirmation=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(beforeOperatorConfirmation.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(beforeOperatorConfirmation.path("reasonCodes").toString()).contains("PRODUCT_CLASSIFICATION_MISSING");
        assertThat(beforeOperatorConfirmation.path("assessmentNotice").isNull()).isTrue();
        assertThat(beforeOperatorConfirmation.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(beforeOperatorConfirmation.path("approvalVerification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        assertThat(beforeOperatorConfirmation.path("classificationVerification").path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(beforeOperatorConfirmation.path("evidence").path("classification").asString()).isEqualTo("INDEPENDENTLY_VERIFIED");
        assertThat(beforeOperatorConfirmation.path("operatorClassificationStatus").asString()).isEqualTo("NOT_RECORDED");
        assertThat(beforeOperatorConfirmation.toString()).doesNotContain(FoodSafetyEvaluation.ALLOWED_NOTICE);

        var confirmationResponse=owner.write(operatorClassificationConfirmationPath(f.product),
                operatorClassificationConfirmation(UUID.randomUUID(),0,"BAKERY","package:category-and-storage-label"));
        assertThat(confirmationResponse.statusCode()).as(confirmationResponse.body()).isEqualTo(201);
        var confirmation=tree(confirmationResponse);
        assertThat(confirmation.path("status").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(confirmation.path("confirmation").path("expectedProductVersion").asLong()).isZero();
        assertThat(confirmation.path("confirmation").path("productVersion").asLong()).isEqualTo(1);
        assertThat(confirmation.path("confirmation").path("confirmedCategory").asString()).isEqualTo("BAKERY");
        assertThat(confirmation.path("confirmation").path("confirmedTemperatureClass").asString()).isEqualTo("AMBIENT");
        assertThat(confirmation.path("confirmation").path("productProfile").asString()).isEqualTo("PACKAGED_SHELF_STABLE_DRY_BAKERY");
        assertThat(confirmation.path("confirmation").path("sourceType").asString()).isEqualTo("ORIGINAL_PACKAGE");
        assertThat(confirmation.path("confirmation").path("evidenceReference").asString()).isEqualTo("package:category-and-storage-label");
        assertThat(confirmation.path("confirmation").path("actorUserId").asString()).isEqualTo(f.owner.id.toString());
        assertThat(confirmation.path("confirmation").path("recordedByMembershipId").asString()).isEqualTo(f.membership(f.owner).toString());
        assertThat(audits("PRODUCT_CLASSIFICATION_CONFIRMED")).isEqualTo(1);

        var staleCategory=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(staleCategory.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(staleCategory.path("classificationVerification").path("outcome").asString()).isEqualTo("PRODUCT_SCOPE_MISMATCH");
        assertThat(staleCategory.path("classificationVerification").path("signedProductVersion").asLong()).isZero();
        assertThat(staleCategory.path("classificationVerification").path("currentProductVersion").asLong()).isEqualTo(1);
        assertThat(staleCategory.path("operatorClassificationStatus").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(staleCategory.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(staleCategory.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(staleCategory.path("assessmentNotice").asString()).isEqualTo(FoodSafetyEvaluation.ALLOWED_NOTICE);
        assertThat(staleCategory.path("reasonCodes")).isEmpty();

        var currentCategory=owner.write(classificationPath(f.product),signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,
                "external-case:bread-category-v1"));
        assertThat(currentCategory.statusCode()).as(currentCategory.body()).isEqualTo(201);
        assertThat(tree(currentCategory).path("outcome").asString()).isEqualTo("VERIFIED");

        // A local assessment remains available with no trusted issuer, key or external policy approval.
        approvalProperties.setEnabled(false);approvalProperties.setTrustedIssuers(Map.of());
        var selfService=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(selfService.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(selfService.path("approvalVerification").path("outcome").asString()).isEqualTo("TRUST_REGISTRY_DISABLED");
        assertThat(selfService.path("classificationVerification").path("outcome").asString()).isEqualTo("TRUST_REGISTRY_DISABLED");
        assertThat(selfService.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(selfService.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(selfService.path("rulesetId").asString()).isEqualTo(ruleset.id());
        assertThat(selfService.path("rulesetDigest").asString()).isEqualTo(ruleset.digestHex());
        assertThat(selfService.path("assessmentNotice").asString()).isEqualTo(FoodSafetyEvaluation.ALLOWED_NOTICE);

        trusted.setEnabled(true);approvalProperties.setTrustedIssuers(Map.of("test-key",trusted));approvalProperties.setEnabled(true);
        var approvalPath=approvalPath(policyId);var submission=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:ambient-pilot-1");
        var submitted=owner.write(approvalPath,submission);assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        var evidence=tree(submitted);var approvalId=UUID.fromString(evidence.path("approvalId").asString());
        assertThat(evidence.path("verification").path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(submitted.body()).doesNotContain("signature","publicKey","privateKey");
        for (var sql:List.of("UPDATE stockrescue.food_safety_policy_approval SET evidence_reference='rewritten' WHERE org_id=:org AND id=:id",
                "DELETE FROM stockrescue.food_safety_policy_approval WHERE org_id=:org AND id=:id"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.org).param("id",approvalId).update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var sale=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(sale.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(sale.path("reasonCodes")).isEmpty();
        assertThat(sale.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(sale.path("approvalVerification").path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(sale.path("classificationVerification").path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(sale.path("classificationVerification").path("signedProductVersion").asLong()).isEqualTo(1);
        assertThat(sale.path("operatorClassificationStatus").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(sale.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(sale.path("evidence").path("policy").asString()).isEqualTo("INDEPENDENTLY_VERIFIED");
        assertThat(sale.path("assessmentNotice").asString()).isEqualTo(FoodSafetyEvaluation.ALLOWED_NOTICE);
        trusted.setEnabled(false);
        var keyDisabled=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(keyDisabled.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(keyDisabled.path("approvalVerification").path("outcome").asString()).isEqualTo("TRUSTED_KEY_DISABLED");
        assertThat(keyDisabled.path("classificationVerification").path("outcome").asString()).isEqualTo("TRUSTED_KEY_DISABLED");
        assertThat(keyDisabled.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(keyDisabled.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        trusted.setEnabled(true);
        approvalProperties.setEnabled(false);
        var trustDisabled=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(trustDisabled.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(trustDisabled.path("approvalVerification").path("outcome").asString()).isEqualTo("TRUST_REGISTRY_DISABLED");
        assertThat(trustDisabled.path("classificationVerification").path("outcome").asString()).isEqualTo("TRUST_REGISTRY_DISABLED");
        assertThat(trustDisabled.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(trustDisabled.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        approvalProperties.setEnabled(true);
        var restored=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(restored.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(restored.path("approvalVerification").path("outcome").asString()).isEqualTo("VERIFIED");
        var transfer=tree(owner.get(eligibility(lotId,f.store,f.zone,Operation.TRANSFER)));
        assertThat(transfer.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(transfer.path("approvalVerification").path("outcome").asString()).isEqualTo("OPERATION_NOT_APPROVED");
        assertThat(transfer.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");

        var replay=owner.write(approvalPath,submission);assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay).path("approvalId").asString()).isEqualTo(approvalId.toString());
        var changed=new HashMap<>(submission);changed.put("evidenceReference","external-case:changed");
        problem(owner.write(approvalPath,changed),409,"CATALOG_CONFLICT");
        problem(f.operator.browser().write(approvalPath,submission),403,"ACCESS_DENIED");
        problem(f.foreign.browser().get(approvalPath),404,"RESOURCE_NOT_FOUND");

        var revoke=Map.of("requestId",UUID.randomUUID(),"reason","Issuer withdrew the reviewed evidence");
        var revoked=owner.write("/api/v1/organizations/"+f.org+"/food-safety-policy-approvals/"+approvalId+"/revocation",revoke);
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(201);
        assertThat(tree(revoked).path("verification").path("outcome").asString()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM stockrescue.food_safety_policy_approval_revocation WHERE org_id=:org AND approval_id=:id")
                .param("org",f.org).param("id",approvalId).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var afterRevocation=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(afterRevocation.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(afterRevocation.path("approvalVerification").path("outcome").asString()).isEqualTo("REVOKED");
        assertThat(afterRevocation.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        var revokeReplay=owner.write("/api/v1/organizations/"+f.org+"/food-safety-policy-approvals/"+approvalId+"/revocation",revoke);
        assertThat(revokeReplay.statusCode()).isEqualTo(200);

        var next=owner.write(policies(),review(1,"NO_NORMALIZATION"));assertThat(next.statusCode()).isEqualTo(201);
        var newVersion=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(newVersion.path("policy").path("version").asLong()).isEqualTo(2);
        assertThat(newVersion.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(newVersion.path("approvalVerification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        assertThat(count("food_safety_policy_approval")).isEqualTo(1);
        assertThat(count("food_safety_policy_approval_revocation")).isEqualTo(1);
        assertThat(audits("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED")).isEqualTo(1);
        assertThat(audits("FOOD_SAFETY_POLICY_APPROVAL_REVOKED")).isEqualTo(1);
        assertThat(count("food_safety_product_classification")).isEqualTo(2);
        assertThat(audits("PRODUCT_CLASSIFICATION_ATTESTED")).isEqualTo(2);
    }

    @Test
    void oneOwnerCanRecordSourceBoundClassificationAndPassTheLimitedRulesWithoutExternalTrust() throws Exception {
        var owner=f.owner.browser();
        var lotId=register("2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);

        var request=operatorClassificationConfirmation(UUID.randomUUID(),0,"BAKERY","package:front-label-category");
        var confirmation=owner.write(operatorClassificationConfirmationPath(f.product),request);
        assertThat(confirmation.statusCode()).as(confirmation.body()).isEqualTo(201);
        assertThat(tree(confirmation).path("status").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(tree(confirmation).path("confirmation").path("actorUserId").asString()).isEqualTo(f.owner.id.toString());
        assertThat(tree(confirmation).path("confirmation").path("expectedProductVersion").asLong()).isZero();
        assertThat(tree(confirmation).path("confirmation").path("productVersion").asLong()).isEqualTo(1);
        assertThat(tree(owner.get(operatorClassificationConfirmationPath(f.product))).path("status").asString())
                .isEqualTo("CURRENT_OPERATOR_CONFIRMED");

        var assessment=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(assessment.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(assessment.path("assessmentBasis").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(assessment.path("operatorClassificationStatus").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(assessment.path("classificationVerification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        assertThat(assessment.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(assessment.path("evidence").path("recall").asString()).isEqualTo("NOT_REPORTED_IN_REVIEWED_EVIDENCE");
        assertThat(assessment.path("evidence").path("damage").asString()).isEqualTo("NOT_REPORTED_IN_REVIEWED_EVIDENCE");
        assertThat(assessment.path("evidence").path("manufacturerSource").path("reference").asString()).isEqualTo("registry-17");
        assertThat(assessment.path("evidence").path("dateLabelSource").path("reference").asString()).isEqualTo("label-photo");
        assertThat(assessment.path("evidence").path("safetyStatusSource").path("reference").asString()).isEqualTo("supplier-notice");
        assertThat(assessment.path("approvalVerification").isNull()).isTrue();
        assertThat(assessment.path("evidence").path("policy").asString()).isEqualTo("PLATFORM_RULESET");
        assertThat(assessment.path("assessmentNotice").asString()).isEqualTo(FoodSafetyEvaluation.ALLOWED_NOTICE);
        assertThat(assessment.path("reasonCodes")).isEmpty();
        assertThat(approvalProperties.isEnabled()).isFalse();
        assertThat(approvalProperties.getTrustedIssuers()).isEmpty();

        var originalProduct=tree(confirmation).path("product");
        var temperatureCorrection=operatorClassificationConfirmation(UUID.randomUUID(),1,"BAKERY","package:storage-instructions");
        temperatureCorrection.put("temperatureClass","CHILLED");
        temperatureCorrection.put("productProfile","OUTSIDE_CURRENT_PILOT");
        var corrected=owner.write(operatorClassificationConfirmationPath(f.product),temperatureCorrection);
        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(201);
        var correctedProduct=tree(owner.get("/api/v1/organizations/"+f.org+"/products/"+f.product));
        assertThat(correctedProduct.path("id")).isEqualTo(originalProduct.path("id"));
        assertThat(correctedProduct.path("sku")).isEqualTo(originalProduct.path("sku"));
        assertThat(correctedProduct.path("version").asLong()).isEqualTo(2);
        assertThat(correctedProduct.path("temperatureClass").asString()).isEqualTo("CHILLED");
        var afterCorrection=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(afterCorrection.path("decision").asString()).isEqualTo("DENIED");
        assertThat(afterCorrection.path("reasonCodes").toString()).contains("PLATFORM_RULESET_SCOPE_UNSUPPORTED","COLD_CHAIN_EVIDENCE_UNVERIFIED","TEMPERATURE_CLASS_MISMATCH");
    }

    @Test
    void unclassifiedProductCanRecordItsTemperatureSourceAndRemainsUnderReview() throws Exception {
        var owner=f.owner.browser();
        var created=owner.write("/api/v1/organizations/"+f.org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","UNKNOWN-CATEGORY",
                "name","Unclassified product","baseUnit","EA","category","UNCLASSIFIED","temperatureClass","AMBIENT"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var product=UUID.fromString(tree(created).path("id").asString());
        var confirmation=operatorClassificationConfirmation(UUID.randomUUID(),0,"UNCLASSIFIED","package:storage-label");
        var recorded=owner.write(operatorClassificationConfirmationPath(product),confirmation);
        assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(201);
        assertThat(tree(recorded).path("status").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(tree(recorded).path("confirmation").path("sourceType").asString()).isEqualTo("ORIGINAL_PACKAGE");
        assertThat(tree(recorded).path("confirmation").path("evidenceReference").asString()).isEqualTo("package:storage-label");
        var lotId=register(product,"UNCLASSIFIED-LOT","2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);
        var assessment=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(assessment.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(assessment.path("operatorClassificationStatus").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(assessment.path("reasonCodes").toString()).contains("PRODUCT_CATEGORY_UNCLASSIFIED","PLATFORM_RULESET_SCOPE_UNSUPPORTED",
                "PRODUCT_CLASSIFICATION_MISSING");
    }

    @Test
    void signedTimestampPrecisionSurvivesPostgresRoundTripAndReadTimeVerification() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).as(policy.body()).isEqualTo(201);
        var policyId=UUID.fromString(tree(policy).path("id").asString());var signer=signer();trust(signer);
        var issuedAt=NOW.minusSeconds(30);
        var validFrom=NOW.minusSeconds(20).plusNanos(123_000_000);
        var validUntil=NOW.plus(Duration.ofDays(1)).plusNanos(123_456_000);
        var submission=signedApproval(f.org,policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:timestamp-precision",
                null,issuedAt,validFrom,validUntil);
        var posted=owner.write(approvalPath(policyId),submission);
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(201);
        assertThat(tree(posted).path("verification").path("outcome").asString()).isEqualTo("VERIFIED");

        var stored=jdbc.sql("SELECT issued_at,valid_from,valid_until FROM stockrescue.food_safety_policy_approval WHERE org_id=:org AND policy_version_id=:policy")
                .param("org",f.org).param("policy",policyId).query((row,index) -> List.of(row.getTimestamp(1).toInstant(),
                        row.getTimestamp(2).toInstant(),row.getTimestamp(3).toInstant())).single();
        assertThat(stored).containsExactly(issuedAt,validFrom,validUntil);
        var read=owner.get(approvalPath(policyId));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(tree(read).path("verification").path("outcome").asString()).isEqualTo("VERIFIED");
        var replay=owner.write(approvalPath(policyId),submission);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay).path("verification").path("outcome").asString()).isEqualTo("VERIFIED");
        var changed=new HashMap<>(submission);changed.put("evidenceReference","external-case:changed");
        problem(owner.write(approvalPath(policyId),changed),409,"CATALOG_CONFLICT");
        assertThat(count("food_safety_policy_approval")).isEqualTo(1);
        assertThat(audits("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED")).isEqualTo(1);
    }

    @Test
    void signedTimestampsWithPrecisionBeyondPostgresMicrosecondsAreRejectedBeforePersistence() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).as(policy.body()).isEqualTo(201);
        var policyId=UUID.fromString(tree(policy).path("id").asString());var signer=signer();trust(signer);
        var exactIssuedAt=NOW.minusSeconds(30);var exactValidFrom=NOW.minusSeconds(20);var exactValidUntil=NOW.plus(Duration.ofDays(1));
        for (var field:List.of("issuedAt","validFrom","validUntil")) {
            var issuedAt=field.equals("issuedAt")?exactIssuedAt.plusNanos(123_456_789):exactIssuedAt;
            var validFrom=field.equals("validFrom")?exactValidFrom.plusNanos(123_456_789):exactValidFrom;
            var validUntil=field.equals("validUntil")?exactValidUntil.plusNanos(123_456_789):exactValidUntil;
            var submission=signedApproval(f.org,policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:overprecision-"+field,
                    null,issuedAt,validFrom,validUntil);
            problem(owner.write(approvalPath(policyId),submission),422,"INVALID_REQUEST");
            assertThat(count("food_safety_policy_approval")).isZero();
            assertThat(audits("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED")).isZero();
        }
    }

    @Test
    void persistedApprovalIsBoundToTheExactRulesetAndLegacySignaturesStayUntrusted() throws Exception {
        var owner=f.owner.browser();var policyResponse=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policyResponse.statusCode()).as(policyResponse.body()).isEqualTo(201);
        var policyId=UUID.fromString(tree(policyResponse).path("id").asString());var signer=signer();trust(signer);
        var submission=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:ruleset-bound");
        var posted=owner.write(approvalPath(policyId),submission);
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(201);
        var approvalId=UUID.fromString(tree(posted).path("approvalId").asString());
        var stored=approvalRepository.byId(f.org,approvalId).orElseThrow();
        var policy=policyRepository.byId(f.org,policyId).orElseThrow();
        assertThat(stored.attestationVersion()).isEqualTo(2);
        assertThat(stored.rulesetId()).isEqualTo(ruleset.id());
        assertThat(HexFormat.of().formatHex(stored.rulesetDigest())).isEqualTo(ruleset.digestHex());

        var matching=new FoodSafetyPolicyApprovalVerifier(approvalProperties,ruleset,signatures)
                .verify(policy,f.org,stored,null,Operation.SALE,NOW);
        assertThat(matching.outcome()).isEqualTo(FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED);
        var replacement=new FoodSafetyRuleset(ruleset.id()+"-replacement",ruleset.manifest()+"\nrevision=changed");
        var mismatch=new FoodSafetyPolicyApprovalVerifier(approvalProperties,replacement,signatures)
                .verify(policy,f.org,stored,null,Operation.SALE,NOW);
        assertThat(mismatch.outcome()).isEqualTo(FoodSafetyPolicyApprovalVerification.Outcome.RULESET_MISMATCH);
        assertThat(mismatch.authorizes(Operation.SALE)).isFalse();
        assertThat(mismatch.signedRulesetId()).isEqualTo(ruleset.id());
        assertThat(mismatch.activeRulesetId()).isEqualTo(replacement.id());

        var legacy=new FoodSafetyPolicyApproval(UUID.randomUUID(),f.org,policy.id(),policy.profileId(),policy.productCategory(),policy.temperatureClass(),
                Set.of(Operation.SALE),FoodSafetyPolicyDigest.of(policy),1,null,null,"test-issuer","test-key",NOW.minusSeconds(30),NOW.minusSeconds(20),
                NOW.plus(Duration.ofDays(1)),"external-case:legacy",new byte[64],f.owner.id,f.membership(f.owner),UUID.randomUUID(),new byte[32],NOW);
        var legacyResult=new FoodSafetyPolicyApprovalVerifier(approvalProperties,ruleset,signatures)
                .verify(policy,f.org,legacy,null,Operation.SALE,NOW);
        assertThat(legacyResult.outcome()).isEqualTo(FoodSafetyPolicyApprovalVerification.Outcome.LEGACY_ATTESTATION);
        assertThat(legacyResult.authorizes(Operation.SALE)).isFalse();
    }

    @Test
    void productClassificationEvidenceIsBoundToOneProductRevisionAndCanBeRevoked() throws Exception {
        var owner=f.owner.browser();var signer=signer();var trusted=trust(signer);var path=classificationPath(f.product);
        var missing=owner.get(path);
        assertThat(tree(missing).path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        var first=signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,"external-case:bread-classification");
        var submitted=owner.write(path,first);
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        var proof=tree(submitted);var firstId=UUID.fromString(proof.path("attestationId").asString());
        assertThat(proof.path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(proof.path("currentSku").asString()).isEqualTo("BREAD");
        assertThat(proof.path("currentCategory").asString()).isEqualTo("BAKERY");
        assertThat(proof.path("currentProductVersion").asLong()).isEqualTo(0);
        assertThat(submitted.body()).doesNotContain("signature","publicKey","privateKey");
        trusted.setEnabled(false);
        assertThat(tree(owner.get(path)).path("outcome").asString()).isEqualTo("TRUSTED_KEY_DISABLED");
        trusted.setEnabled(true);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        assertThat(tree(owner.get(path)).path("outcome").asString()).isEqualTo("EXPIRED");
        when(clock.instant()).thenReturn(NOW);
        var stored=jdbc.sql("SELECT sku,product_category,product_version FROM stockrescue.food_safety_product_classification WHERE org_id=:org AND id=:id")
                .param("org",f.org).param("id",firstId).query((row,index) -> List.of(row.getString(1),row.getString(2),row.getLong(3))).single();
        assertThat(stored).containsExactly("BREAD","BAKERY",0L);

        var replay=owner.write(path,first);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay).path("attestationId").asString()).isEqualTo(firstId.toString());
        var changed=new HashMap<>(first);changed.put("evidenceReference","external-case:changed");
        problem(owner.write(path,changed),409,"CATALOG_CONFLICT");
        assertThat(count("food_safety_product_classification")).isEqualTo(1);
        for (var sql:List.of("UPDATE stockrescue.food_safety_product_classification SET evidence_reference='rewritten' WHERE org_id=:org AND id=:id",
                "DELETE FROM stockrescue.food_safety_product_classification WHERE org_id=:org AND id=:id"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.org).param("id",firstId).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);

        var categoryConfirmation=operatorClassificationConfirmation(UUID.randomUUID(),0,"DAIRY","supplier:category-sheet");
        var confirmed=owner.write(operatorClassificationConfirmationPath(f.product),categoryConfirmation);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(201);
        assertThat(tree(confirmed).path("status").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        var stale=tree(owner.get(path));
        assertThat(stale.path("outcome").asString()).isEqualTo("PRODUCT_SCOPE_MISMATCH");
        assertThat(stale.path("signedCategory").asString()).isEqualTo("BAKERY");
        assertThat(stale.path("signedProductVersion").asLong()).isZero();
        assertThat(stale.path("currentCategory").asString()).isEqualTo("DAIRY");
        assertThat(stale.path("currentProductVersion").asLong()).isEqualTo(1);
        var lotId=register("2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);
        var afterCorrection=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(afterCorrection.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(afterCorrection.path("reasonCodes").toString()).contains("PLATFORM_RULESET_SCOPE_UNSUPPORTED");
        assertThat(afterCorrection.path("classificationVerification").path("outcome").asString()).isEqualTo("PRODUCT_SCOPE_MISMATCH");
        assertThat(afterCorrection.path("operatorClassificationStatus").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(afterCorrection.path("evidence").path("classification").asString()).isEqualTo("OPERATOR_CONFIRMED");

        var staleBody=signedClassification(f.org,f.product,ProductCategory.DAIRY,signer,"external-case:stale-version");
        staleBody.put("expectedProductVersion",0);
        problem(owner.write(path,staleBody),409,"CATALOG_CONFLICT");
        var renewed=signedClassification(f.org,f.product,ProductCategory.DAIRY,signer,"external-case:dairy-classification");
        var renewedResult=owner.write(path,renewed);
        assertThat(renewedResult.statusCode()).as(renewedResult.body()).isEqualTo(201);
        assertThat(tree(renewedResult).path("outcome").asString()).isEqualTo("VERIFIED");
        var renewedId=UUID.fromString(tree(renewedResult).path("attestationId").asString());
        var revokePath=classificationRevocationPath(f.product,renewedId);
        var revocation=Map.of("requestId",UUID.randomUUID(),"reason","Issuer withdrew the product classification source");
        var revoked=owner.write(revokePath,revocation);
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(201);
        assertThat(tree(revoked).path("outcome").asString()).isEqualTo("REVOKED");
        assertThat(tree(owner.get(path)).path("outcome").asString()).isEqualTo("REVOKED");
        var afterRevocation=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(afterRevocation.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(afterRevocation.path("reasonCodes").toString()).contains("PLATFORM_RULESET_SCOPE_UNSUPPORTED");
        assertThat(afterRevocation.path("classificationVerification").path("outcome").asString()).isEqualTo("REVOKED");
        assertThat(owner.write(revokePath,revocation).statusCode()).isEqualTo(200);
        assertThat(count("food_safety_product_classification")).isEqualTo(2);
        assertThat(count("food_safety_product_classification_revocation")).isEqualTo(1);
        assertThat(audits("PRODUCT_CLASSIFICATION_ATTESTED")).isEqualTo(2);
        assertThat(audits("PRODUCT_CLASSIFICATION_REVOKED")).isEqualTo(1);
    }

    @Test
    void independentlyVerifiedPolicyCannotAuthorizeAProductWithoutClassificationEvidence() throws Exception {
        var owner=f.owner.browser();var lotId=register("2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);
        var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).as(policy.body()).isEqualTo(201);
        var signer=signer();trust(signer);var policyId=UUID.fromString(tree(policy).path("id").asString());
        var approval=owner.write(approvalPath(policyId),signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:policy-only"));
        assertThat(approval.statusCode()).as(approval.body()).isEqualTo(201);

        var result=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(result.path("approvalVerification").path("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(result.path("classificationVerification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        assertThat(result.path("evidence").path("classification").asString()).isEqualTo("MISSING");
        assertThat(result.path("reasonCodes").toString()).contains("PRODUCT_CLASSIFICATION_MISSING");
    }

    @Test
    void operatorCategoryConfirmationAndEligibilityUseOneConsistentProductClassificationSnapshot() throws Exception {
        var owner=f.owner.browser();var signer=signer();trust(signer);var path=classificationPath(f.product);
        var attested=owner.write(path,signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,"external-case:concurrent-correction"));
        assertThat(attested.statusCode()).as(attested.body()).isEqualTo(201);
        var lotId=register("2027-04-30","USE_BY","DAY");
        assertThat(owner.write(statusPath(lotId),status(0,"NOT_REPORTED","NOT_REPORTED")).statusCode()).isEqualTo(201);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Evaluation barrier timed out");
            return call.callRealMethod();
        }).when(classifications).latest(f.org,f.product);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var evaluation=workers.submit(() -> owner.get(eligibility(lotId,f.store,f.zone)));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var confirmation=owner.write(operatorClassificationConfirmationPath(f.product),
                    operatorClassificationConfirmation(UUID.randomUUID(),0,"DAIRY","supplier:category-sheet"));
            assertThat(confirmation.statusCode()).as(confirmation.body()).isEqualTo(201);
            release.countDown();
            var beforeCorrection=tree(evaluation.get(15,TimeUnit.SECONDS));
            assertThat(beforeCorrection.path("classificationVerification").path("outcome").asString()).isEqualTo("VERIFIED");
            assertThat(beforeCorrection.path("classificationVerification").path("currentCategory").asString()).isEqualTo("BAKERY");
            assertThat(beforeCorrection.path("classificationVerification").path("currentProductVersion").asLong()).isZero();
        } finally { release.countDown(); }

        var afterCorrection=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(afterCorrection.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(afterCorrection.path("classificationVerification").path("outcome").asString()).isEqualTo("PRODUCT_SCOPE_MISMATCH");
        assertThat(afterCorrection.path("operatorClassificationStatus").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(afterCorrection.path("reasonCodes").toString()).contains("PLATFORM_RULESET_SCOPE_UNSUPPORTED");
    }

    @Test
    void policySignaturesAndOtherProductCategoriesCannotBeReplayedAsClassificationEvidence() throws Exception {
        var owner=f.owner.browser();var signer=signer();trust(signer);var path=classificationPath(f.product);
        var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).isEqualTo(201);
        var policyId=UUID.fromString(tree(policy).path("id").asString());
        var policySignature=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:policy-signature");
        var policyBytes=(String)policySignature.get("signatureBase64");
        var replayed=new HashMap<>(signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,"external-case:classification"));
        replayed.put("signatureBase64",policyBytes);
        problem(owner.write(path,replayed),422,"INVALID_REQUEST");

        var wrongCategory=signedClassification(f.org,f.product,ProductCategory.DAIRY,signer,"external-case:wrong-category");
        problem(owner.write(path,wrongCategory),422,"INVALID_REQUEST");
        var wrongOrganization=signedClassification(f.otherOrg,f.product,ProductCategory.BAKERY,signer,"external-case:wrong-organization");
        problem(owner.write(path,wrongOrganization),422,"INVALID_REQUEST");
        problem(owner.get(classificationPath(UUID.randomUUID())),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_product_classification")).isZero();
        assertThat(audits("PRODUCT_CLASSIFICATION_ATTESTED")).isZero();
    }

    @Test
    void failedClassificationAuditRollsBackAttestationAndAllowsExactRetry() throws Exception {
        var owner=f.owner.browser();var signer=signer();trust(signer);var path=classificationPath(f.product);
        var submission=signedClassification(f.org,f.product,ProductCategory.BAKERY,signer,"external-case:audit-rollback");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),eq("product-classification"),any(),
                eq("PRODUCT_CLASSIFICATION_ATTESTED"),anyMap(),anyString());
        problem(owner.write(path,submission),500,"INTERNAL_ERROR");
        assertThat(count("food_safety_product_classification")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),eq("product-classification"),any(),
                eq("PRODUCT_CLASSIFICATION_ATTESTED"),anyMap(),anyString());
        assertThat(owner.write(path,submission).statusCode()).isEqualTo(201);
        assertThat(count("food_safety_product_classification")).isEqualTo(1);
        assertThat(audits("PRODUCT_CLASSIFICATION_ATTESTED")).isEqualTo(1);
    }

    @Test
    void approvalSubmissionFailsClosedWithoutExternalTrustOrWhenTheSignatureIsTampered() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).isEqualTo(201);var policyId=UUID.fromString(tree(policy).path("id").asString());
        var signer=signer();var path=approvalPath(policyId);
        var untrusted=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:untrusted");
        problem(owner.write(path,untrusted),422,"INVALID_REQUEST");
        assertThat(count("food_safety_policy_approval")).isZero();
        assertThat(tree(owner.get(path)).path("verification").path("outcome").asString()).isEqualTo("TRUST_REGISTRY_DISABLED");

        trust(signer);var tampered=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:tampered");
        var signature=Base64.getDecoder().decode((String)tampered.get("signatureBase64"));signature[0]^=1;
        tampered.put("signatureBase64",Base64.getEncoder().encodeToString(signature));
        problem(owner.write(path,tampered),422,"INVALID_REQUEST");
        assertThat(count("food_safety_policy_approval")).isZero();
        assertThat(tree(owner.get(eligibility(register("2027-04-30","USE_BY","DAY"),f.store,f.zone)))
                .path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
    }

    @Test
    void expiredAndWrongDigestAttestationsCannotBeRecordedAsApproval() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).isEqualTo(201);var policyId=UUID.fromString(tree(policy).path("id").asString());
        var signer=signer();trust(signer);var path=approvalPath(policyId);
        var wrongDigest=signedApproval(f.org,policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:wrong-digest",
                "a".repeat(64),NOW.minusSeconds(30),NOW.minusSeconds(20),NOW.plus(Duration.ofDays(1)));
        problem(owner.write(path,wrongDigest),422,"INVALID_REQUEST");
        var expired=signedApproval(f.org,policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:expired",null,
                NOW.minus(Duration.ofDays(2)),NOW.minus(Duration.ofDays(1)),NOW.minusSeconds(1));
        problem(owner.write(path,expired),422,"INVALID_REQUEST");
        assertThat(count("food_safety_policy_approval")).isZero();
        var otherScope=policyPath("DAIRY","AMBIENT")+"/"+policyId+"/approval-evidence";
        problem(owner.write(otherScope,expired),404,"RESOURCE_NOT_FOUND");
        assertThat(tree(owner.get(path)).path("verification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
    }

    @Test
    void approvalAndRevocationAuditsCommitAtomicallyWithTheirImmutableEvidence() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).isEqualTo(201);var policyId=UUID.fromString(tree(policy).path("id").asString());
        var signer=signer();trust(signer);var path=approvalPath(policyId);
        var submission=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:rollback");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),eq("food-safety-policy-approval"),any(),
                eq("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED"),anyMap(),anyString());
        problem(owner.write(path,submission),500,"INTERNAL_ERROR");assertThat(count("food_safety_policy_approval")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),eq("food-safety-policy-approval"),any(),
                eq("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED"),anyMap(),anyString());
        var created=owner.write(path,submission);assertThat(created.statusCode()).isEqualTo(201);
        var approvalId=UUID.fromString(tree(created).path("approvalId").asString());var revocationPath="/api/v1/organizations/"+f.org+
                "/food-safety-policy-approvals/"+approvalId+"/revocation";var revoke=Map.of("requestId",UUID.randomUUID(),"reason","Issuer withdrew evidence");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),eq("food-safety-policy-approval"),eq(approvalId),
                eq("FOOD_SAFETY_POLICY_APPROVAL_REVOKED"),anyMap(),anyString());
        problem(owner.write(revocationPath,revoke),500,"INTERNAL_ERROR");assertThat(count("food_safety_policy_approval_revocation")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),eq("food-safety-policy-approval"),eq(approvalId),
                eq("FOOD_SAFETY_POLICY_APPROVAL_REVOKED"),anyMap(),anyString());
        assertThat(owner.write(revocationPath,revoke).statusCode()).isEqualTo(201);
        assertThat(count("food_safety_policy_approval")).isEqualTo(1);assertThat(count("food_safety_policy_approval_revocation")).isEqualTo(1);
    }

    @Test
    void policyVersionAppendWaitsForAnInFlightApprovalAndCannotInheritItsEvidence() throws Exception {
        var owner=f.owner.browser();var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).isEqualTo(201);var policyId=UUID.fromString(tree(policy).path("id").asString());
        var signer=signer();trust(signer);var path=approvalPath(policyId);
        var submission=signedApproval(policyId,UUID.randomUUID(),Set.of(Operation.SALE),signer,"external-case:concurrency");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown();if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out");return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.org),eq("food-safety-policy-approval"),any(),
                        eq("FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED"),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            var submit=workers.submit(() -> owner.write(path,submission));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var append=workers.submit(() -> f.coowner.browser().write(policies(),review(1,"NO_NORMALIZATION")));
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%' ").query(Long.class).single()).isPositive());
            release.countDown();assertThat(submit.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(201);assertThat(append.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        } finally { release.countDown(); }
        var current=tree(owner.get(policies()));assertThat(current.path("current").path("version").asLong()).isEqualTo(2);
        assertThat(current.path("items").get(1).path("id").asString()).isEqualTo(policyId.toString());
        assertThat(tree(owner.get(approvalPath(UUID.fromString(current.path("current").path("id").asString()))))
                .path("verification").path("outcome").asString()).isEqualTo("NOT_SUBMITTED");
        assertThat(count("food_safety_policy_approval")).isEqualTo(1);
    }

    @Test
    void evaluationUsesCurrentStoreDateAndIsReadOnlyAndUncacheable() throws Exception {
        var id=register("2026-11-05","USE_BY","DAY"); var owner=f.owner.browser();
        var original=tree(owner.get(lot(id))); var auditCount=count("audit_entry");
        var response=owner.get(eligibility(id,f.store,f.zone));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var result=tree(response);
        assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(result.path("storeLocalDate").asString()).isEqualTo("2026-11-05");
        assertThat(result.path("storeTimeZone").asString()).isEqualTo("Europe/Warsaw");
        assertThat(result.path("policy").isNull()).isTrue();
        assertThat(result.path("reasonCodes").toString()).contains("PRODUCT_CLASSIFICATION_MISSING").doesNotContain("USE_BY_EXPIRED");
        when(clock.instant()).thenReturn(Instant.parse("2026-11-05T23:00:00Z"));
        var tomorrow=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(tomorrow.path("decision").asString()).isEqualTo("DENIED");
        assertThat(tomorrow.path("reasonCodes").get(0).asString()).isEqualTo("USE_BY_EXPIRED");
        assertThat(tomorrow.path("storeLocalDate").asString()).isEqualTo("2026-11-06");
        assertThat(tomorrow.path("evaluatedAt").asString()).isEqualTo("2026-11-05T23:00:00Z");
        assertThat(tree(owner.get(lot(id)))).isEqualTo(original);
        assertThat(count("audit_entry")).isEqualTo(auditCount); assertThat(count("lot_correction")).isZero();
    }

    @Test
    void policyReviewsRemainUnverifiedAndNewVersionsReplaceCurrentEvaluationEvidence() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY"); var owner=f.owner.browser();
        assertThat(tree(owner.get(policies())).path("current").isNull()).isTrue();
        var body=review(0,"NO_NORMALIZATION"); var first=owner.write(policies(),body);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(tree(first).path("approvalStatus").asString()).isEqualTo("UNVERIFIED");
        var initialEvaluation=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(initialEvaluation.path("policy").path("id")).isEqualTo(tree(first).path("id"));
        assertThat(initialEvaluation.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        var second=owner.write(policies(),review(1,"END_OF_LABELED_PERIOD"));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(tree(second).path("profileId")).isEqualTo(tree(first).path("profileId"));
        var fresh=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(fresh.path("policy").path("version").asLong()).isEqualTo(2);
        assertThat(fresh.path("policy").path("id")).isEqualTo(tree(second).path("id"));
        assertThat(initialEvaluation.path("policy").path("version").asLong()).isEqualTo(1);
        assertThat(tree(owner.write(policies(),body))).isEqualTo(tree(first));
        var altered=new HashMap<>(body); altered.put("reason","Changed replay");
        problem(owner.write(policies(),altered),409,"CATALOG_CONFLICT");
        var page=tree(owner.get(policies()+"?limit=1"));
        assertThat(page.path("current")).isEqualTo(tree(second));assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(owner.get(policies()+"?offset=1&limit=1")).path("items").get(0)).isEqualTo(tree(first));
        assertThat(tree(owner.get(policies().replace("AMBIENT","CHILLED"))).path("current").isNull()).isTrue();
        assertThat(count("food_safety_policy_version")).isEqualTo(2);
        assertThat(audits("FOOD_SAFETY_POLICY_REVIEWED")).isEqualTo(2);
    }

    @Test
    void policyHistoryAndEligibilityAreIsolatedByProductCategoryWithinOneTemperatureClass() throws Exception {
        var owner=f.owner.browser();
        var bakeryPolicy=owner.write(policyPath("BAKERY","CHILLED"),review(0,"NO_NORMALIZATION"));
        assertThat(bakeryPolicy.statusCode()).as(bakeryPolicy.body()).isEqualTo(201);
        problem(f.foreign.browser().get(policyPath("BAKERY","CHILLED")),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().write(policyPath("BAKERY","CHILLED"),review(0,"NO_NORMALIZATION")),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_policy_version")).isEqualTo(1);
        assertThat(tree(owner.get(policyPath("DAIRY","CHILLED"))).path("current").isNull()).isTrue();
        var dairyProduct=owner.write("/api/v1/organizations/"+f.org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","MILK-SCOPE","name","Packaged milk","baseUnit","EA","category","DAIRY","temperatureClass","CHILLED"));
        assertThat(dairyProduct.statusCode()).as(dairyProduct.body()).isEqualTo(201);
        var bakeryProduct=owner.write("/api/v1/organizations/"+f.org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","BREAD-SCOPE","name","Packaged bread","baseUnit","EA","category","BAKERY","temperatureClass","CHILLED"));
        assertThat(bakeryProduct.statusCode()).as(bakeryProduct.body()).isEqualTo(201);
        var dairyProductId=UUID.fromString(tree(dairyProduct).path("id").asString());
        var bakeryProductId=UUID.fromString(tree(bakeryProduct).path("id").asString());
        var dairyLotId=register(dairyProductId,"MILK-SCOPE-LOT","2027-04-30","USE_BY","DAY");
        var bakeryLotId=register(bakeryProductId,"BREAD-SCOPE-LOT","2027-04-30","USE_BY","DAY");
        var missing=tree(owner.get(eligibility(dairyLotId,f.store,f.incompatibleZone)));
        assertThat(missing.path("policy").isNull()).isTrue();
        assertThat(missing.path("reasonCodes").toString()).contains("PLATFORM_RULESET_SCOPE_UNSUPPORTED");
        var bakeryMatch=tree(owner.get(eligibility(bakeryLotId,f.store,f.incompatibleZone)));
        assertThat(bakeryMatch.path("policy").path("id")).isEqualTo(tree(bakeryPolicy).path("id"));
        assertThat(bakeryMatch.path("policy").path("productCategory").asString()).isEqualTo("BAKERY");
        var dairyPolicy=owner.write(policyPath("DAIRY","CHILLED"),review(0,"NO_NORMALIZATION"));
        assertThat(dairyPolicy.statusCode()).as(dairyPolicy.body()).isEqualTo(201);
        assertThat(tree(dairyPolicy).path("productCategory").asString()).isEqualTo("DAIRY");
        assertThat(tree(dairyPolicy).path("version").asLong()).isEqualTo(1);
        assertThat(tree(bakeryPolicy).path("productCategory").asString()).isEqualTo("BAKERY");
        assertThat(tree(bakeryPolicy).path("profileId")).isNotEqualTo(tree(dairyPolicy).path("profileId"));
        var matched=tree(owner.get(eligibility(dairyLotId,f.store,f.incompatibleZone)));
        assertThat(matched.path("policy").path("id")).isEqualTo(tree(dairyPolicy).path("id"));
        assertThat(matched.path("policy").path("productCategory").asString()).isEqualTo("DAIRY");
        assertThat(tree(owner.get(policyPath("BAKERY","CHILLED"))).path("current").path("id")).isEqualTo(tree(bakeryPolicy).path("id"));
    }

    @Test
    void concurrentOperatorClassificationConfirmationAndPolicyReviewUseTheCurrentScope() throws Exception {
        var owner=f.owner.browser();
        var initialPolicy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(initialPolicy.statusCode()).as(initialPolicy.body()).isEqualTo(201);
        var lotId=register("2027-04-30","USE_BY","DAY");
        var productPath="/api/v1/organizations/"+f.org+"/products/"+f.product;
        var toDairy=owner.write(operatorClassificationConfirmationPath(f.product),
                operatorClassificationConfirmation(UUID.randomUUID(),0,"DAIRY","package:category-label"));
        assertThat(toDairy.statusCode()).as(toDairy.body()).isEqualTo(201);

        var confirmation=operatorClassificationConfirmation(UUID.randomUUID(),1,"BAKERY","package:rechecked-label");
        var outcomes=concurrent(
                () -> owner.write(operatorClassificationConfirmationPath(f.product),confirmation),
                () -> owner.write(policyPath("BAKERY"),review(1,"NO_NORMALIZATION")));
        assertThat(outcomes.get(0).statusCode()).as(outcomes.get(0).body()).isEqualTo(201);
        assertThat(outcomes.get(1).statusCode()).as(outcomes.get(1).body()).isEqualTo(201);

        var current=tree(owner.get(eligibility(lotId,f.store,f.zone)));
        assertThat(current.path("policy").path("productCategory").asString()).isEqualTo("BAKERY");
        assertThat(current.path("policy").path("version").asLong()).isEqualTo(2);
        assertThat(current.path("policy").path("id")).isEqualTo(tree(outcomes.get(1)).path("id"));
        assertThat(current.path("decision").asString()).isNotEqualTo("ALLOWED");
    }

    @Test
    void partialLabelsAndRequestedNormalizationNeverProduceCutoffsOrApproval() throws Exception {
        var owner=f.owner.browser(); assertThat(owner.write(policies(),review(0,"END_OF_LABELED_PERIOD")).statusCode()).isEqualTo(201);
        for (var example:new String[][]{{" 2027-02 ","BEST_BEFORE_END","MONTH","PARTIAL_DATE_RULE_UNVERIFIED"},{"2027","BEST_BEFORE_END","YEAR","PARTIAL_DATE_RULE_UNVERIFIED"},
                {null,"UNKNOWN","UNKNOWN","DATE_LABEL_MISSING"},{"2027-02-31","USE_BY","DAY","DATE_LABEL_INVALID"}}) {
            var id=register(example[0],example[1],example[2]); var result=tree(owner.get(eligibility(id,f.store,f.zone)));
            assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
            assertThat(result.path("reasonCodes").toString()).contains(example[3]).doesNotContain("ALLOWED");
            var detail=tree(owner.get(lot(id)));
            assertThat(detail.path("dateMarking").path("normalizedCutoff").isNull()).isTrue();
            assertThat(detail.path("version").asLong()).isZero();
            if (example[0]!=null) assertThat(detail.path("dateMarking").path("labeledValue").asString()).isEqualTo(example[0]);
        }
    }

    @Test
    void administratorsCannotOverrideExpiryRecallDamageTemperatureOrApprovalVerification() throws Exception {
        var owner=f.owner.browser();
        for (var field:List.of("allowExpiredUseBy","allowExpiredBestBefore","ignoreRecall","ignoreDamage","ignoreTemperatureMismatch","allowMissingEvidence","approvalStatus")) {
            var permissive=review(0,"NO_NORMALIZATION"); permissive.put(field,field.equals("approvalStatus")?"VERIFIED":true);
            problem(owner.write(policies(),permissive),400,"INVALID_REQUEST");
        }
        var missingSource=review(0,"NO_NORMALIZATION");missingSource.put("reviewProvenance",Map.of("source","Admin statement"));
        problem(owner.write(policies(),missingSource),422,"INVALID_REQUEST");
        assertThat(owner.write(policies(),review(0,"NO_NORMALIZATION")).statusCode()).isEqualTo(201);
        var expired=register("2026-11-04","BEST_BEFORE","DAY");
        assertThat(tree(owner.get(eligibility(expired,f.store,f.zone))).path("decision").asString()).isEqualTo("DENIED");
        var id=register("2027-04-30","USE_BY","DAY");
        var result=tree(owner.get(eligibility(id,f.store,f.incompatibleZone)));
        assertThat(result.path("decision").asString()).isEqualTo("DENIED");
        assertThat(result.path("reasonCodes").toString()).contains("TEMPERATURE_CLASS_MISMATCH");
        assertThat(count("food_safety_policy_version")).isEqualTo(1);
    }

    @Test
    void anUnverifiedPolicyCannotMakeAnInvalidOriginalDateEligible() throws Exception {
        var owner=f.owner.browser();
        var policy=owner.write(policies(),review(0,"NO_NORMALIZATION"));
        assertThat(policy.statusCode()).as(policy.body()).isEqualTo(201);
        var id=register("2026-02-31","USE_BY","DAY");
        var saved=tree(owner.get(lot(id))).path("dateMarking");
        assertThat(saved.path("labeledValue").asString()).isEqualTo("2026-02-31");
        assertThat(saved.path("validationStatus").asString()).isEqualTo("INVALID");
        var result=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(result.path("evidence").path("date").asString()).isEqualTo("INVALID");
        assertThat(result.path("reasonCodes").toString()).contains("DATE_LABEL_INVALID")
                .doesNotContain("USE_BY_EXPIRED","ALLOWED");
    }

    @Test
    void blockEvidenceUsesTheSameImmutableHistoryAndCannotBeClearedByMetadata() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY"); var owner=f.owner.browser(); var before=tree(owner.get(lot(id)));
        var body=status(0,"RECALLED","DAMAGED");var result=owner.write(statusPath(id),body);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        var record=tree(result).path("correction");
        assertThat(record.path("kind").asString()).isEqualTo("SAFETY_STATUS");
        assertThat(record.path("before")).isEqualTo(before);
        assertThat(record.path("after")).isEqualTo(tree(owner.get(lot(id))));
        assertThat(record.path("after").path("dateMarking")).isEqualTo(before.path("dateMarking"));
        var evaluation=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(evaluation.path("decision").asString()).isEqualTo("DENIED");
        assertThat(evaluation.path("reasonCodes").get(0).asString()).isEqualTo("LOT_RECALLED");
        assertThat(evaluation.path("reasonCodes").get(1).asString()).isEqualTo("LOT_DAMAGED");
        assertThat(evaluation.path("lotVersion").asLong()).isEqualTo(1);
        assertThat(tree(owner.write(statusPath(id),body))).isEqualTo(tree(result));
        problem(owner.write(statusPath(id),status(1,"NOT_REPORTED","NOT_REPORTED")),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.get(lot(id)+"/corrections")).path("items").get(0)).isEqualTo(record);
        var changes=json.readTree(jdbc.sql("SELECT changes::text FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_SAFETY_EVIDENCE_RECORDED'")
                .param("org",f.org).query(String.class).single());
        assertThat(changes.path("before").path("recallState").asString()).isEqualTo("UNKNOWN");
        assertThat(changes.path("after").path("recallState").asString()).isEqualTo("RECALLED");
        assertThat(audits("LOT_SAFETY_EVIDENCE_RECORDED")).isEqualTo(1);
    }

    @Test
    void policyAndSafetyEvidenceWritesRequireExplicitAuthorityAndEvaluationRequiresTheCurrentStoreGrant() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var operator=f.operator.browser(); var owner=f.owner.browser();
        for (var role:List.of("INVENTORY_OPERATOR","STORE_MANAGER","READ_ONLY_ANALYST")) {
            jdbc.sql("UPDATE stockrescue.store_grant SET role=:role,version=version+1 WHERE org_id=:org").param("org",f.org).param("role",role).update();
            assertThat(operator.get(eligibility(id,f.store,f.zone)).statusCode()).isEqualTo(200);
            problem(operator.write(policies(),review(0,"NO_NORMALIZATION")),403,"ACCESS_DENIED");
            problem(operator.write(statusPath(id),status(0,"RECALLED","UNKNOWN")),403,"ACCESS_DENIED");
        }
        problem(operator.get(eligibility(id,f.otherStore,f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(eligibility(id,f.store,f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(eligibility(UUID.randomUUID(),f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().write("/api/v1/organizations/"+f.otherOrg+"/lots/"+id+"/safety-status-evidence",status(0,"RECALLED","UNKNOWN")),404,"RESOURCE_NOT_FOUND");
        problem(new Browser().get(eligibility(id,f.store,f.zone)),401,"AUTH_REQUIRED");
        problem(owner.send("POST",policies(),review(0,"NO_NORMALIZATION"),null),403,"CSRF_INVALID");
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org").param("org",f.org).update();
        problem(operator.get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_policy_version")).isZero();assertThat(count("lot_correction")).isZero();
    }

    @Test
    void inactiveContextDeniesWhileMissingStorageRemainsExplicitlyUnverified() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var owner=f.owner.browser();
        var unknown=tree(owner.get(eligibility(id,f.store,null)));
        assertThat(unknown.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(unknown.path("evidence").path("storage").asString()).isEqualTo("MISSING");
        jdbc.sql("UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.zone).update();
        var archived=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(archived.path("decision").asString()).isEqualTo("DENIED");
        assertThat(archived.path("reasonCodes").toString()).contains("ZONE_ARCHIVED");
        jdbc.sql("UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.store).update();
        assertThat(tree(owner.get(eligibility(id,f.store,f.zone))).path("reasonCodes").toString()).contains("STORE_ARCHIVED");
        problem(f.operator.browser().get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
    }

    @Test
    void concurrentPolicyAppendsHaveOneWinnerAndIdenticalRetriesRecoverTheOriginalVersion() throws Exception {
        var one=f.owner.browser();var two=new Browser(f.owner);
        var results=concurrent(() -> one.write(policies(),review(0,"NO_NORMALIZATION")),() -> two.write(policies(),review(0,"END_OF_LABELED_PERIOD")));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        var next=review(1,"NO_NORMALIZATION");
        var duplicate=concurrent(() -> one.write(policies(),next),() -> two.write(policies(),next));
        assertThat(duplicate.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(duplicate.get(0))).isEqualTo(tree(duplicate.get(1)));
        assertThat(count("food_safety_policy_version")).isEqualTo(2);assertThat(audits("FOOD_SAFETY_POLICY_REVIEWED")).isEqualTo(2);
    }

    @Test
    void aStatusWriteAndDateCorrectionOnOneVersionCannotOverwriteEachOther() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var one=f.owner.browser();var two=new Browser(f.owner);
        var metadata=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Reviewed original label","evidenceReviewed",true,
                "identityState","CONFIRMED","manufacturerVerified",true,"manufacturerIdentityKey","registry:maker-a",
                "manufacturerProvenance",provenance("registry-17"),"dateMarking",marking("2027-05-01","USE_BY","DAY"));
        var results=concurrent(() -> one.write(statusPath(id),status(0,"RECALLED","UNKNOWN")),() -> two.write(lot(id)+"/corrections",metadata));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        assertThat(tree(one.get(lot(id))).path("version").asLong()).isEqualTo(1);
        assertThat(count("lot_correction")).isEqualTo(1);
        assertThat(audits("LOT_METADATA_CORRECTED")+audits("LOT_SAFETY_EVIDENCE_RECORDED")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackBothPolicyAndSafetyEvidenceWithTheirRetryResults() throws Exception {
        var owner=f.owner.browser();var policy=review(0,"NO_NORMALIZATION");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq("FOOD_SAFETY_POLICY_REVIEWED"),anyMap(),anyString());
        problem(owner.write(policies(),policy),500,"INTERNAL_ERROR");assertThat(count("food_safety_policy_version")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq("FOOD_SAFETY_POLICY_REVIEWED"),anyMap(),anyString());
        assertThat(owner.write(policies(),policy).statusCode()).isEqualTo(201);
        var id=register("2027-04-30","USE_BY","DAY");var before=tree(owner.get(lot(id)));var status=status(0,"RECALLED","UNKNOWN");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_SAFETY_EVIDENCE_RECORDED"),anyMap(),anyString());
        problem(owner.write(statusPath(id),status),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(lot(id)))).isEqualTo(before);assertThat(count("lot_correction")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_SAFETY_EVIDENCE_RECORDED"),anyMap(),anyString());
        assertThat(owner.write(statusPath(id),status).statusCode()).isEqualTo(201);
    }

    @Test
    void databaseGuardsPreventUnverifiedApprovalHistoryMutationAndUnrecordedStatusUpdates() throws Exception {
        var owner=f.owner.browser();assertThat(owner.write(policies(),review(0,"NO_NORMALIZATION")).statusCode()).isEqualTo(201);
        for (var sql:List.of("UPDATE stockrescue.food_safety_policy_version SET approval_status='VERIFIED' WHERE org_id=:org",
                "DELETE FROM stockrescue.food_safety_policy_version WHERE org_id=:org"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.org).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO stockrescue.food_safety_policy_version(id,org_id,profile_id,temperature_class,version,partial_date_rule,approval_status,
                    review_source,review_reference,reason,actor_user_id,recorded_by_membership_id,request_id,payload_digest)
                SELECT :id,org_id,profile_id,temperature_class,version+1,partial_date_rule,'VERIFIED',review_source,review_reference,reason,
                    actor_user_id,recorded_by_membership_id,:request,payload_digest FROM stockrescue.food_safety_policy_version WHERE org_id=:org
                """).param("id",UUID.randomUUID()).param("request",UUID.randomUUID()).param("org",f.org).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var id=register("2027-04-30","USE_BY","DAY");
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.manufacturer_lot SET recall_state='RECALLED',status_source='Claim',status_reference='ref',version=version+1 WHERE org_id=:org AND id=:id")
                .param("org",f.org).param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(tree(owner.get(lot(id))).path("recallState").asString()).isEqualTo("UNKNOWN");
        assertThat(count("food_safety_policy_version")).isEqualTo(1);assertThat(count("lot_correction")).isZero();
    }

    @Test void aCommittedRevocationRejectsTheWaitingPolicyReview() throws Exception { orderedPolicy(false); }
    @Test void anAuthorizedPolicyReviewCommitsBeforeRevocation() throws Exception { orderedPolicy(true); }
    private void orderedPolicy(boolean writeFirst) throws Exception {
        var owner=f.owner.browser();var admin=f.coowner.browser();var command=review(0,"NO_NORMALIZATION");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var action=writeFirst?"FOOD_SAFETY_POLICY_REVIEWED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown();if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out");return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq(action),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> review=() -> owner.write(policies(),command);
            Callable<HttpResponse<String>> revoke=() -> admin.send("DELETE","/api/v1/organizations/"+f.org+"/members/"+f.membership(f.owner),Map.of("version",0,"reason","Assignment ended"),admin.token());
            var first=workers.submit(writeFirst?review:revoke);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(writeFirst?revoke:review);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%'").query(Long.class).single()).isPositive());
            release.countDown();assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?200:404);
        } finally { release.countDown(); }
        problem(owner.write(policies(),command),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_policy_version")).isEqualTo(writeFirst?1:0);
    }

    private KeyPair signer() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
    private FoodSafetyApprovalProperties.TrustedIssuer trust(KeyPair signer) {
        var trusted=new FoodSafetyApprovalProperties.TrustedIssuer();trusted.setIssuerId("test-issuer");
        trusted.setPublicKeyX509(Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));trusted.setEnabled(true);
        approvalProperties.setTrustedIssuers(Map.of("test-key",trusted));approvalProperties.setEnabled(true);return trusted;
    }
    private Map<String,Object> signedApproval(UUID policyId,UUID requestId,Set<Operation> operations,KeyPair signer,String evidenceReference) throws Exception {
        return signedApproval(f.org,policyId,requestId,operations,signer,evidenceReference,null,NOW.minusSeconds(30),NOW.minusSeconds(20),NOW.plus(Duration.ofDays(1)));
    }
    private Map<String,Object> signedApproval(UUID signingOrganization,UUID policyId,UUID requestId,Set<Operation> operations,KeyPair signer,
            String evidenceReference,String digestOverride,Instant issuedAt,Instant validFrom,Instant validUntil) throws Exception {
        var policy=policyRepository.byId(f.org,policyId).orElseThrow();var digest=digestOverride==null?FoodSafetyPolicyDigest.hex(policy):digestOverride;
        var issuer="test-issuer";var key="test-key";
        var payload=FoodSafetyPolicyAttestation.canonicalPayload(signingOrganization,policy,HexFormat.of().parseHex(digest),ruleset.id(),ruleset.digest(),operations,issuer,key,
                issuedAt,validFrom,validUntil,evidenceReference);
        var signature=Signature.getInstance("Ed25519");signature.initSign(signer.getPrivate());signature.update(payload);
        var body=new HashMap<String,Object>();body.put("requestId",requestId);body.put("issuerId",issuer);body.put("keyId",key);body.put("policyDigest",digest);
        body.put("rulesetId",ruleset.id());body.put("rulesetDigest",ruleset.digestHex());
        body.put("allowedOperations",operations.stream().map(Enum::name).sorted().toList());body.put("issuedAt",issuedAt);body.put("validFrom",validFrom);
        body.put("validUntil",validUntil);body.put("evidenceReference",evidenceReference);body.put("signatureBase64",Base64.getEncoder().encodeToString(signature.sign()));
        return body;
    }
    private Map<String,Object> signedClassification(UUID signingOrganization,UUID productId,ProductCategory category,KeyPair signer,
            String evidenceReference) throws Exception {
        var product=productRepository.detail(f.org,productId,false).orElseThrow();
        var issuedAt=NOW.minusSeconds(30);var validFrom=NOW.minusSeconds(20);var validUntil=NOW.plus(Duration.ofDays(1));
        var issuer="test-issuer";var key="test-key";
        var payload=FoodSafetyProductClassificationAttestation.canonicalPayload(signingOrganization,product,category,issuer,key,
                issuedAt,validFrom,validUntil,evidenceReference);
        var signature=Signature.getInstance("Ed25519");signature.initSign(signer.getPrivate());signature.update(payload);
        return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedProductVersion",product.version(),"issuerId",issuer,"keyId",key,
                "issuedAt",issuedAt,"validFrom",validFrom,"validUntil",validUntil,"evidenceReference",evidenceReference,
                "signatureBase64",Base64.getEncoder().encodeToString(signature.sign())));
    }
    private Map<String,Object> review(long version,String rule) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Review of class-specific source evidence","partialDateRule",rule,"reviewProvenance",provenance("policy-document"))); }
    private Map<String,Object> status(long version,String recall,String damage) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Reviewed supplier safety notice","recallState",recall,"damageState",damage,"provenance",provenance("supplier-notice"))); }
    private Map<String,Object> marking(String label,String type,String precision) { var body=new HashMap<String,Object>(Map.of("type",type,"precision",precision,"provenance",provenance("label-photo")));if (label!=null) body.put("labeledValue",label);return body; }
    private Map<String,String> provenance(String reference) { return Map.of("source","Observed document","reference",reference); }
    private UUID register(String label,String type,String precision) throws Exception {
        return register(f.product,UUID.randomUUID().toString(),label,type,precision);
    }
    private UUID register(UUID product,String lotCode,String label,String type,String precision) throws Exception {
        var body=Map.of("requestId",UUID.randomUUID(),"productId",product,"lotCode",lotCode,"identityState","CONFIRMED","manufacturerIdentityKey","registry:maker-a",
                "manufacturerVerified",true,"manufacturerProvenance",provenance("registry-17"),"dateMarking",marking(label,type,precision));
        var result=f.owner.browser().write("/api/v1/organizations/"+f.org+"/lots",body);assertThat(result.statusCode()).as(result.body()).isEqualTo(201);return UUID.fromString(tree(result).path("id").asString());
    }
    private String lot(UUID id) { return "/api/v1/organizations/"+f.org+"/lots/"+id; }
    private String policies() { return "/api/v1/organizations/"+f.org+"/food-safety-profiles/BAKERY/AMBIENT/versions"; }
    private String approvalPath(UUID policyId) { return policies()+"/"+policyId+"/approval-evidence"; }
    private Map<String,Object> operatorClassificationConfirmation(UUID request,long version,String category,String reference) {
        var body=new HashMap<String,Object>();body.put("requestId",request);body.put("expectedProductVersion",version);body.put("category",category);
        body.put("temperatureClass","AMBIENT");
        body.put("productProfile",category.equals("BAKERY")?"PACKAGED_SHELF_STABLE_DRY_BAKERY":
                category.equals("UNCLASSIFIED")?"NOT_ASSESSED":"OUTSIDE_CURRENT_PILOT");
        if (reference!=null) {
            body.put("sourceType",reference.startsWith("supplier:")?"SUPPLIER_DOCUMENT":"ORIGINAL_PACKAGE");
            body.put("evidenceReference",reference);
        } else { body.put("sourceType","ORIGINAL_PACKAGE");body.put("evidenceReference","package:source-review"); }
        body.put("reason","Reviewed category and declared temperature against the cited product evidence");return body;
    }
    private String operatorClassificationConfirmationPath(UUID productId) { return classificationPath(productId)+"/operator-confirmations"; }
    private String classificationPath(UUID productId) { return "/api/v1/organizations/"+f.org+"/products/"+productId+"/classification-evidence"; }
    private String classificationRevocationPath(UUID productId,UUID attestationId) { return classificationPath(productId)+"/"+attestationId+"/revocation"; }
    private String policyPath(String category) { return "/api/v1/organizations/"+f.org+"/food-safety-profiles/"+category+"/AMBIENT/versions"; }
    private String policyPath(String category,String temperature) { return "/api/v1/organizations/"+f.org+"/food-safety-profiles/"+category+"/"+temperature+"/versions"; }
    private String statusPath(UUID id) { return lot(id)+"/safety-status-evidence"; }
    private String eligibility(UUID id,UUID store,UUID zone) { return lot(id)+"/eligibility?operation=SALE&storeId="+store+(zone==null?"":"&zoneId="+zone); }
    private String eligibility(UUID id,UUID store,UUID zone,Operation operation) { return lot(id)+"/eligibility?operation="+operation+"&storeId="+store+(zone==null?"":"&zoneId="+zone); }
    private JsonNode tree(HttpResponse<String> result) { return json.readTree(result.body()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.org).param("action",action).query(Long.class).single(); }
    private void problem(HttpResponse<String> result,int status,String code) { assertThat(result.statusCode()).as(result.body()).isEqualTo(status);assertThat(tree(result).path("code").asString()).isEqualTo(code); }
    private List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception { var start=new CountDownLatch(1);try (var workers=Executors.newFixedThreadPool(2)) { var a=workers.submit(() -> { start.await();return one.call(); });var b=workers.submit(() -> { start.await();return two.call(); });start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)); } }
    private class Fixture {
        final User owner=user(),coowner=user(),operator=user(),foreign=user();
        final UUID org=org(owner),otherOrg=org(foreign),store=store("NORTH"),otherStore=store("SOUTH"),zone=zone(store,"AMBIENT"),incompatibleZone=zone(store,"CHILLED"),otherZone=zone(otherStore,"AMBIENT"),product;
        Fixture() throws Exception {
            member(owner,org,"ORG_ADMIN");member(coowner,org,"ORG_ADMIN");member(operator,org,"MEMBER");member(foreign,otherOrg,"ORG_ADMIN");
            jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,'INVENTORY_OPERATOR')")
                    .param("id",UUID.randomUUID()).param("org",org).param("member",membership(operator)).param("store",store).update();
            var result=owner.browser().write("/api/v1/organizations/"+org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","BREAD","name","Packaged bread","baseUnit","EA","category","BAKERY","temperatureClass","AMBIENT"));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(201);product=UUID.fromString(tree(result).path("id").asString());
        }
        User user() { var u=new User(UUID.randomUUID());jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",u.id).param("email",u.email).update();jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",u.id).param("hash",hash).update();return u; }
        UUID org(User owner) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Safety cooperative',:owner)").param("id",id).param("owner",owner.id).update();return id; }
        UUID store(String code) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:code,'Europe/Warsaw')").param("id",id).param("org",org).param("code",code).update();return id; }
        UUID zone(UUID store,String temperature) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,:name,:temperature)").param("id",id).param("org",org).param("store",store).param("name",temperature).param("temperature",temperature).update();return id; }
        void member(User actor,UUID org,String role) { jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,:role)").param("id",UUID.randomUUID()).param("org",org).param("actor",actor.id).param("role",role).update(); }
        UUID membership(User actor) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:actor").param("org",org).param("actor",actor.id).query(UUID.class).single(); }
    }
    private class User { final UUID id;final String email;Browser browser;User(UUID id) { this.id=id;this.email=id+"@example.test"; }Browser browser() throws Exception { if (browser==null) browser=new Browser(this);return browser; } }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
        Browser(User actor) throws Exception { var result=write("/api/v1/auth/sign-in",Map.of("email",actor.email,"password",PASSWORD));assertThat(result.statusCode()).as(result.body()).isEqualTo(200); }
        String token() throws Exception { return tree(get("/api/v1/auth/csrf")).path("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String path,Object body) throws Exception { return send("POST",path,body,token()); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception { var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");if (token!=null) request.header("X-CSRF-TOKEN",token);return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
