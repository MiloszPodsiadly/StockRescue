package com.stockrescue.identity.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "contract"})
class OpenApiContractTest extends InfrastructureTestBase {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;

    @Test
    void reviewedContractMatchesBackendRoutesAndSchemas() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs.yaml"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var contract = response.body().replace("\r\n", "\n");
        var path = Path.of("../contracts/http/openapi.yaml");
        if (Boolean.getBoolean("updateApiContract")) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contract, StandardCharsets.UTF_8);
        } else {
            assertThat(contract).isEqualTo(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        assertThat(contract).contains("/api/v1/auth/sign-in", "/api/v1/auth/sign-up", "X-CSRF-TOKEN", "SRSESSION")
                .contains("CsrfResponse:", "UserResponse:", "RegistrationResponse:", "Credentials:")
                .contains("/api/v1/organizations:", "/api/v1/organizations/{organizationId}:",
                        "/api/v1/auth/active-organization:", "/api/v1/auth/me/effective-permissions:", "OnboardingRequest:", "PermissionsResponse:")
                .doesNotContain("/api/health", "password_hash");
        var schemaRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var document=json.readTree(HttpClient.newHttpClient().send(schemaRequest,HttpResponse.BodyHandlers.ofString()).body());
        var schemas=document.path("components").path("schemas");
        var policyPath="/api/v1/organizations/{organizationId}/food-safety-profiles/{productCategory}/{temperatureClass}/versions";
        assertThat(document.path("paths").path(policyPath).path("post").path("operationId").asString()).isEqualTo("recordFoodSafetyPolicyReview");
        assertThat(document.path("paths").path(policyPath).path("get").path("operationId").asString()).isEqualTo("listFoodSafetyPolicyVersions");
        var lotPath="/api/v1/organizations/{organizationId}/lots";
        assertThat(document.path("paths").path(lotPath).path("post").path("operationId").asString()).isEqualTo("registerManufacturerLot");
        var sourcePath=lotPath+"/{lotId}/source-evidence";
        var correctionPath=lotPath+"/{lotId}/corrections";
        var eligibilityPath=lotPath+"/{lotId}/eligibility";
        assertThat(document.path("paths").path(eligibilityPath).path("get").path("operationId").asString()).isEqualTo("evaluateLotEligibility");
        assertThat(document.path("paths").path(lotPath+"/{lotId}/safety-status-evidence").path("post").path("operationId").asString()).isEqualTo("recordLotSafetyEvidence");
        var decisions=schemas.path("EligibilityResponse").path("properties").path("decision").path("enum");
        assertThat(decisions).extracting(value -> value.asString()).containsExactly("ALLOWED","DENIED","REQUIRES_REVIEW");
        assertThat(schemas.path("EligibilityResponse").path("properties").path("policy").path("anyOf")).hasSize(2);
        assertThat(schemas.path("FoodSafetyPolicyHistoryResponse").path("properties").path("current").path("anyOf")).hasSize(2);
        var policyInput=schemas.path("RecordFoodSafetyPolicyReviewRequest").path("properties");
        assertThat(policyInput.has("approvalStatus")).isFalse();
        assertThat(policyInput.has("allowExpiredUseBy")).isFalse();
        assertThat(schemas.path("FoodSafetyPolicyVersionResponse").path("properties").path("approvalStatus").path("enum").toString()).isEqualTo("[\"UNVERIFIED\"]");
        var signedApproval=schemas.path("SubmitPolicyApprovalEvidenceRequest");
        for (var field:java.util.List.of("rulesetId","rulesetDigest")) {
            assertThat(signedApproval.path("properties").has(field)).isTrue();
            assertThat(signedApproval.path("required")).anyMatch(value -> value.asString().equals(field));
        }
        var approvalVerification=schemas.path("ApprovalVerificationResponse");
        for (var field:java.util.List.of("attestationVersion","signedRulesetId","signedRulesetDigest","activeRulesetId","activeRulesetDigest"))
            assertThat(approvalVerification.path("properties").has(field)).isTrue();
        assertThat(approvalVerification.path("properties").path("outcome").path("enum").toString()).contains("LEGACY_ATTESTATION","RULESET_MISMATCH");
        assertThat(document.path("paths").path(correctionPath).path("post").path("operationId").asString()).isEqualTo("correctLotMetadata");
        assertThat(document.path("paths").path(correctionPath).path("get").path("operationId").asString()).isEqualTo("listLotCorrections");
        for (var field:java.util.List.of("requestId","expectedVersion","reason","evidenceReviewed"))
            assertThat(schemas.path("CorrectLotMetadataRequest").path("required")).anyMatch(value -> value.asString().equals(field));
        for (var field:java.util.List.of("before","after","actorUserId","actorMembershipId","recordedAt","resultVersion"))
            assertThat(schemas.path("LotCorrectionResponse").path("properties").has(field)).isTrue();
        assertThat(document.path("paths").path(sourcePath).path("post").path("operationId").asString()).isEqualTo("registerSourceEvidence");
        assertThat(document.path("paths").path(sourcePath).path("get").path("operationId").asString()).isEqualTo("traceLotSources");
        assertThat(schemas.path("DateMarkingResponse").path("properties").path("provenance").path("anyOf")).hasSize(2);
        var validation=schemas.path("DateMarkingResponse").path("properties").path("validationStatus").path("enum").toString();
        assertThat(validation).contains("VALID","INCOMPLETE","INVALID").doesNotContain("ALLOWED");
        for (var field:java.util.List.of("labeledValue","type","precision","validationStatus","validationReasons","normalizedCutoff"))
            assertThat(schemas.path("DateMarkingResponse").path("properties").has(field)).isTrue();
        assertThat(schemas.path("SourceEvidenceResponse").path("properties").has("quantity")).isFalse();
        assertThat(schemas.path("SourceEvidenceResponse").path("properties").has("receivedAt")).isFalse();
        var productPath="/api/v1/organizations/{organizationId}/products";
        var productOperations=document.path("paths").path(productPath);
        assertThat(productOperations.path("post").path("operationId").asString()).isEqualTo("createProduct");
        assertThat(productOperations.path("get").path("operationId").asString()).isEqualTo("listProducts");
        assertThat(productOperations.path("post").path("responses").has("422")).isTrue();
        var gtinReviewPath=productPath+"/gtin-review";
        assertThat(document.path("paths").path(gtinReviewPath).path("get").path("operationId").asString()).isEqualTo("reviewProductGtin");
        var gtinReviewSchema=schemas.path("GtinReviewResponse");
        assertThat(gtinReviewSchema.path("properties").path("matches").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/GtinMatchResponse");
        assertThat(gtinReviewSchema.path("properties").has("hasMore")).isTrue();
        assertThat(gtinReviewSchema.path("properties").path("matchSetRevision").path("type")).anyMatch(value -> value.asString().equals("null"));
        var gtinMatchSchema=schemas.path("GtinMatchResponse");
        for (var field:java.util.List.of("productId","sku","name","status","gtin"))
            assertThat(gtinMatchSchema.path("properties").has(field)).isTrue();
        assertThat(gtinMatchSchema.path("properties").path("status").path("enum").toString()).isEqualTo("[\"ACTIVE\",\"ARCHIVED\"]");
        var createProductRequest=schemas.path("CreateProductRequest");
        for (var field:java.util.List.of("gtinReviewAcknowledged","gtinReviewRevision")) {
            assertThat(createProductRequest.path("properties").has(field)).isTrue();
            assertThat(createProductRequest.path("required")).noneMatch(value -> value.asString().equals(field));
        }
        assertThat(schemas.path("ProductResponse").path("properties").path("gtin").path("type")).anyMatch(value -> value.asString().equals("null"));
        assertThat(schemas.path("ProductResponse").path("properties").path("category").path("enum").toString()).isEqualTo("[\"UNCLASSIFIED\",\"DAIRY\",\"BAKERY\"]");
        assertThat(schemas.path("CreateProductRequest").path("required")).anyMatch(value -> value.asString().equals("category"));
        var classificationPath=productPath+"/{productId}/classification-evidence";
        assertThat(document.path("paths").path(classificationPath).path("get").path("operationId").asString()).isEqualTo("getProductClassificationEvidence");
        assertThat(document.path("paths").path(classificationPath).path("post").path("operationId").asString()).isEqualTo("submitProductClassificationEvidence");
        var operatorConfirmationPath=classificationPath+"/operator-confirmations";
        assertThat(document.path("paths").path(operatorConfirmationPath).path("get").path("operationId").asString())
                .isEqualTo("getOperatorProductClassificationConfirmation");
        assertThat(document.path("paths").path(operatorConfirmationPath).path("post").path("operationId").asString())
                .isEqualTo("confirmOperatorProductClassification");
        assertThat(document.path("paths").has(productPath+"/{productId}/category-corrections")).isFalse();
        var operatorConfirmationRequest=schemas.path("ConfirmOperatorClassificationRequest");
        for (var field:java.util.List.of("requestId","expectedProductVersion","category","reason")) {
            assertThat(operatorConfirmationRequest.path("properties").has(field)).isTrue();
            assertThat(operatorConfirmationRequest.path("required")).anyMatch(value -> value.asString().equals(field));
        }
        assertThat(schemas.path("OperatorClassificationConfirmationResponse").path("description").asString())
                .contains("not independent");
        assertThat(document.path("paths").path(classificationPath+"/{attestationId}/revocation").path("post").path("operationId").asString())
                .isEqualTo("revokeProductClassificationEvidence");
        var classificationRequest=schemas.path("SubmitProductClassificationEvidenceRequest");
        for (var field:java.util.List.of("requestId","expectedProductVersion","issuerId","keyId","issuedAt","validFrom","validUntil","evidenceReference","signatureBase64")) {
            assertThat(classificationRequest.path("properties").has(field)).isTrue();
            assertThat(classificationRequest.path("required")).anyMatch(value -> value.asString().equals(field));
        }
        assertThat(schemas.path("ProductClassificationEvidenceResponse").path("properties").has("signature")).isFalse();
        assertThat(schemas.path("EligibilityResponse").path("properties").has("classificationVerification")).isTrue();
        for (var field:java.util.List.of("productCategory","productTemperatureClass")) {
            assertThat(schemas.path("EligibilityResponse").path("properties").has(field)).isTrue();
            assertThat(schemas.path("EligibilityResponse").path("required")).anyMatch(value -> value.asString().equals(field));
        }
        assertThat(schemas.path("EligibilityEvidenceResponse").path("properties").has("classification")).isTrue();
        for (var field:java.util.List.of("manufacturerSource","dateLabelSource","safetyStatusSource"))
            assertThat(schemas.path("EligibilityEvidenceResponse").path("properties").has(field)).isTrue();
        var archiveProduct=document.path("paths").path(productPath+"/{productId}/archive").path("post");
        assertThat(archiveProduct.path("operationId").asString()).isEqualTo("archiveProduct");
        var productArchiveProperties=schemas.path("ArchiveProductRequest").path("properties");
        for (var field:java.util.List.of("requestId","expectedVersion","reason")) assertThat(productArchiveProperties.has(field)).isTrue();
        for (var field : java.util.Map.of("LaneResponse", "currentCost", "LaneLookupResponse", "lane").entrySet()) {
            var union = schemas.path(field.getKey()).path("properties").path(field.getValue()).path("anyOf");
            assertThat(union).hasSize(2);
            assertThat(union.get(0).path("$ref").asString()).startsWith("#/components/schemas/");
            assertThat(union.get(1).path("type").asString()).isEqualTo("null");
        }
        var temperature = schemas.path("RevisionResponse").path("properties").path("temperatureClass");
        assertThat(temperature.path("enum")).anyMatch(value -> value.isNull());
        var cancellationPath="/api/v1/organizations/{organizationId}/transport-lanes/{laneId}/scheduled-revisions/cancel";
        assertThat(schemas.path("RevisionResponse").path("properties").path("cancellation").path("anyOf")).hasSize(2);
        var cancellationProperties=schemas.path("CancelledRevisionResponse").path("properties");
        for(var field:java.util.List.of("id","revisionId","revision","cancelledByMembershipId","reason","cancelledAt"))
            assertThat(cancellationProperties.has(field)).isTrue();
        var resultProperties=schemas.path("ScheduledRevisionCancellationResponse").path("properties");
        assertThat(resultProperties.has("lane")).isTrue();
        assertThat(resultProperties.has("cancelledRevisions")).isTrue();
        var operation=document.path("paths").path(cancellationPath).path("post");
        assertThat(operation.path("operationId").asString()).isEqualTo("cancelScheduledLaneRevisions");
        assertThat(operation.path("responses").has("200")).isTrue();
        var resolutionPath="/api/v1/organizations/{organizationId}/transport-lanes/{laneId}/scheduled-revisions/cancellation-resolution";
        var resolutionOperation=document.path("paths").path(resolutionPath).path("post");
        assertThat(resolutionOperation.path("operationId").asString()).isEqualTo("resolveScheduledLaneCancellation");
        assertThat(resolutionOperation.path("responses").has("200")).isTrue();
        var resolutionRequest=schemas.path("ScheduledRevisionCancellationResolutionRequest").path("properties");
        assertThat(resolutionRequest.path("reviewedRevisionIds").path("maxItems").asInt()).isEqualTo(384);
        var resolutionProperties=schemas.path("ScheduledRevisionCancellationResolutionResponse").path("properties");
        for(var field:java.util.List.of("lane","decision","reviewedRevisions","hasFutureRevisions","laneChanged"))
            assertThat(resolutionProperties.has(field)).isTrue();
        var reviewedResolution=schemas.path("ReviewedCancellationResolutionResponse").path("properties");
        for(var field:java.util.List.of("revisionId","revision","effectiveAt","status","cancellation"))
            assertThat(reviewedResolution.has(field)).isTrue();

        var archiveImpactPath="/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones/{zoneId}/archive-impact";
        var archiveImpact=document.path("paths").path(archiveImpactPath).path("get");
        assertThat(archiveImpact.path("operationId").asString()).isEqualTo("previewStorageZoneArchiveImpact");
        assertThat(archiveImpact.path("responses").has("200")).isTrue();
        var impactProperties=schemas.path("ZoneArchiveImpactResponse").path("properties");
        for(var field:java.util.List.of("activeLaneCount","inactiveLaneCount","futureScheduledRevisionCount","activeLocationCount","zoneVersion","impactFingerprint","asOf"))
            assertThat(impactProperties.has(field)).isTrue();
        var archiveOperation=document.path("paths").path("/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones/{zoneId}/archive").path("post");
        assertThat(archiveOperation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ArchiveZoneRequest");
        assertThat(schemas.path("ArchiveZoneRequest").path("properties").has("impactFingerprint")).isTrue();
        var locationPath="/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations";
        assertThat(document.path("paths").path(locationPath).path("post").path("operationId").asString()).isEqualTo("createStorageLocation");
        assertThat(document.path("paths").path(locationPath+"/{locationId}/archive").path("post").path("operationId").asString()).isEqualTo("archiveStorageLocation");
        var locationSchema=schemas.path("StorageLocationResponse").path("properties");
        for(var field:java.util.List.of("id","code","zoneId","temperatureClass","active","usable","version","archivedAt")) assertThat(locationSchema.has(field)).isTrue();
        for(var field:java.util.List.of("quantity","capacity","availableQuantity")) assertThat(locationSchema.has(field)).isFalse();
        assertThat(schemas.path("EligibilityResponse").path("properties").has("locationVersion")).isTrue();
    }
}
