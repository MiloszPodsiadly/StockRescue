package com.stockrescue.catalog.http;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationService;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationSubmission;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationSubmission;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationRevocationCommand;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationVerification;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.platform.http.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/products/{productId}/classification-evidence",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed evidence fields",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization or product evidence unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Changed retry, stale product version or existing revocation",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid or unverified classification signature",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class FoodSafetyProductClassificationController {
    private final FoodSafetyProductClassificationService classifications;
    public FoodSafetyProductClassificationController(FoodSafetyProductClassificationService classifications) { this.classifications=classifications; }

    @GetMapping
    @Operation(operationId="getProductClassificationEvidence",summary="Read current independent classification evidence for the exact product revision without exposing its signature")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ProductClassificationEvidenceResponse.class)))
    public ResponseEntity<ProductClassificationEvidenceResponse> evidence(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ProductClassificationEvidenceResponse.from(classifications.evidence(actor(actor),organizationId,productId)));
    }

    @GetMapping("/operator-confirmations")
    @Operation(operationId="getOperatorProductClassificationConfirmation",summary="Read the latest attributed operator classification and whether it matches the current product revision")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class)))
    public ResponseEntity<OperatorClassificationConfirmationResponse> operatorConfirmation(Authentication actor,
            @PathVariable UUID organizationId,@PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                OperatorClassificationConfirmationResponse.from(classifications.confirmation(actor(actor),organizationId,productId)));
    }

    @PostMapping(value="/operator-confirmations",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="confirmOperatorProductClassification",summary="Record a source-attributed operator category confirmation and bind it to a new product version")
    @ApiResponses({
            @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class))),
            @ApiResponse(responseCode="200",description="Previously recorded confirmation replayed",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class)))})
    public ResponseEntity<OperatorClassificationConfirmationResponse> confirmOperatorClassification(Authentication actor,
            @PathVariable UUID organizationId,@PathVariable UUID productId,
            @Valid @RequestBody ConfirmOperatorClassificationRequest request) {
        var submission=new FoodSafetyProductClassificationConfirmationSubmission(request.requestId(),request.expectedProductVersion(),
                request.category(),request.sourceType(),request.evidenceReference(),request.reason());
        var result=classifications.confirm(actor(actor),organizationId,productId,submission);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(OperatorClassificationConfirmationResponse.from(result));
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="submitProductClassificationEvidence",summary="Submit domain-separated external Ed25519 evidence for the product's current descriptive category and version")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=ProductClassificationEvidenceResponse.class))),
            @ApiResponse(responseCode="200",description="Previously recorded immutable evidence",content=@Content(schema=@Schema(implementation=ProductClassificationEvidenceResponse.class)))})
    public ResponseEntity<ProductClassificationEvidenceResponse> submit(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId,
            @Valid @RequestBody SubmitProductClassificationEvidenceRequest request) {
        var result=classifications.submit(actor(actor),organizationId,productId,request.value());
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(ProductClassificationEvidenceResponse.from(result.evidence()));
    }

    @PostMapping(value="/{attestationId}/revocation",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="revokeProductClassificationEvidence",summary="Append an immutable revocation of one product classification attestation")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=ProductClassificationEvidenceResponse.class))),
            @ApiResponse(responseCode="200",description="Previously recorded revocation",content=@Content(schema=@Schema(implementation=ProductClassificationEvidenceResponse.class)))})
    public ResponseEntity<ProductClassificationEvidenceResponse> revoke(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId,
            @PathVariable UUID attestationId,@Valid @RequestBody RevokeProductClassificationEvidenceRequest request) {
        var result=classifications.revoke(actor(actor),organizationId,productId,attestationId,
                new FoodSafetyProductClassificationRevocationCommand(request.requestId(),request.reason()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(ProductClassificationEvidenceResponse.from(result.evidence()));
    }

    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }

    public record SubmitProductClassificationEvidenceRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedProductVersion,
            @NotBlank @Size(max=128) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String issuerId,
            @NotBlank @Size(max=128) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String keyId,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant issuedAt,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant validFrom,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant validUntil,
            @NotBlank @Size(max=256) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="External source pointer only; source documents are never submitted") String evidenceReference,
            @NotBlank @Size(min=88,max=88) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Canonical Base64 Ed25519 signature; never returned by the API") String signatureBase64) {
        FoodSafetyProductClassificationSubmission value() {
            return new FoodSafetyProductClassificationSubmission(requestId,expectedProductVersion,issuerId,keyId,issuedAt,validFrom,
                    validUntil,evidenceReference,signatureBase64);
        }
    }

    public record RevokeProductClassificationEvidenceRequest(@NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }

    public record ConfirmOperatorClassificationRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedProductVersion,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Descriptive category confirmed from the cited source; UNCLASSIFIED stays under review") ProductCategory category,
            @Schema(types={"string","null"},description="ORIGINAL_PACKAGE, SUPPLIER_DOCUMENT or OTHER; required for a known category") FoodSafetyProductClassificationConfirmation.SourceType sourceType,
            @Schema(types={"string","null"},description="Reference to the inspected source; source content is not uploaded") @Size(max=256) String evidenceReference,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }

    public record OperatorClassificationConfirmationDetails(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sku,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long expectedProductVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long productVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory originalCategory,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory confirmedCategory,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyProductClassificationConfirmation.SourceType sourceType,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actorUserId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID recordedByMembershipId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt) {
        static OperatorClassificationConfirmationDetails from(FoodSafetyProductClassificationConfirmation value) {
            return new OperatorClassificationConfirmationDetails(value.id(),value.organizationId(),value.productId(),value.sku(),
                    value.expectedProductVersion(),value.productVersion(),value.originalCategory(),value.confirmedCategory(),
                    value.sourceType(),value.evidenceReference(),value.actorUserId(),value.recordedByMembershipId(),value.requestId(),
                    value.reason(),value.recordedAt());
        }
    }

    @Schema(description="Operator-confirmed category evidence is attributed and source-referenced; it is not independent or specialist verification.")
    public record OperatorClassificationConfirmationResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductController.ProductResponse product,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) OperatorClassificationConfirmationDetails confirmation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyProductClassificationConfirmation.Status status) {
        static OperatorClassificationConfirmationResponse from(FoodSafetyProductClassificationService.ConfirmationResult value) {
            return new OperatorClassificationConfirmationResponse(ProductController.ProductResponse.from(value.product()),
                    value.confirmation()==null?null:OperatorClassificationConfirmationDetails.from(value.confirmation()),value.status());
        }
    }

    public record ProductClassificationEvidenceResponse(
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID attestationId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyProductClassificationVerification.Outcome outcome,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String currentSku,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory currentCategory,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long currentProductVersion,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory signedCategory,
            @Schema(types={"integer","null"},requiredMode=Schema.RequiredMode.REQUIRED) Long signedProductVersion,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String issuerId,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String keyId,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant issuedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant validFrom,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant validUntil,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean revoked,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant revokedAt,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String revocationReason) {
        static ProductClassificationEvidenceResponse from(FoodSafetyProductClassificationService.Evidence value) {
            return from(value.verification());
        }
        static ProductClassificationEvidenceResponse from(FoodSafetyProductClassificationVerification proof) {
            return new ProductClassificationEvidenceResponse(proof.attestationId(),proof.outcome(),proof.organizationId(),proof.productId(),proof.currentSku(),
                    proof.currentCategory(),proof.currentProductVersion(),proof.signedCategory(),proof.signedProductVersion(),proof.issuerId(),
                    proof.keyId(),proof.issuedAt(),proof.validFrom(),proof.validUntil(),proof.evidenceReference(),proof.recordedAt(),proof.revoked(),
                    proof.revokedAt(),proof.revocationReason());
        }
    }
}
