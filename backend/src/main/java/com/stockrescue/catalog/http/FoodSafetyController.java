package com.stockrescue.catalog.http;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.stockrescue.catalog.application.*;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.*;
import com.stockrescue.catalog.application.FoodSafetyPolicyVersion.*;
import com.stockrescue.catalog.application.ManufacturerLot.DamageState;
import com.stockrescue.catalog.application.ManufacturerLot.RecallState;
import com.stockrescue.catalog.http.CatalogEvidenceDtos.*;
import com.stockrescue.catalog.http.LotController.LotCorrectionResult;
import com.stockrescue.catalog.http.LotController.LotCorrectionResponse;
import com.stockrescue.catalog.http.LotController.LotResponse;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
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
@RequestMapping(value="/api/v1/organizations/{organizationId}",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed fields or unsupported policy override",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Scoped resource unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Version, replay or immutable block conflict",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid review evidence",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class FoodSafetyController {
    private final FoodSafetyPolicyService policies; private final FoodSafetyEvaluationService evaluations; private final LotService lots;
    public FoodSafetyController(FoodSafetyPolicyService policies,FoodSafetyEvaluationService evaluations,LotService lots) { this.policies=policies;this.evaluations=evaluations;this.lots=lots; }
    @PostMapping(value="/food-safety-profiles/{temperatureClass}/versions",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="recordFoodSafetyPolicyReview",summary="Append an immutable class-specific policy review; a submitted reference never grants independent approval")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=FoodSafetyPolicyVersionResponse.class))),
            @ApiResponse(responseCode="200",description="Previously recorded immutable version",content=@Content(schema=@Schema(implementation=FoodSafetyPolicyVersionResponse.class)))})
    public ResponseEntity<FoodSafetyPolicyVersionResponse> review(Authentication actor,@PathVariable UUID organizationId,@PathVariable TemperatureClass temperatureClass,
            @Valid @RequestBody RecordFoodSafetyPolicyReviewRequest request) {
        var result=policies.review(actor(actor),organizationId,temperatureClass,request.requestId(),
                new FoodSafetyPolicyReview(request.expectedVersion(),request.reason(),request.partialDateRule(),request.reviewProvenance().value()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(FoodSafetyPolicyVersionResponse.from(result.version()));
    }
    @GetMapping("/food-safety-profiles/{temperatureClass}/versions")
    @Operation(operationId="listFoodSafetyPolicyVersions",summary="Read latest review and immutable version history; an absent profile is explicit")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=FoodSafetyPolicyHistoryResponse.class)))
    public ResponseEntity<FoodSafetyPolicyHistoryResponse> history(Authentication actor,@PathVariable UUID organizationId,@PathVariable TemperatureClass temperatureClass,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=policies.history(actor(actor),organizationId,temperatureClass,offset,limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FoodSafetyPolicyHistoryResponse(FoodSafetyPolicyVersionResponse.from(page.current()),
                page.items().stream().map(FoodSafetyPolicyVersionResponse::from).toList(),page.hasMore()));
    }
    @GetMapping("/lots/{lotId}/eligibility")
    @Operation(operationId="evaluateLotEligibility",summary="Recompute a read-only decision for a lot, operation and authorized store context; review is never executable approval")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=EligibilityResponse.class)))
    public ResponseEntity<EligibilityResponse> evaluate(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @RequestParam UUID storeId,@RequestParam(required=false) UUID zoneId,@RequestParam(required=false) UUID locationId,@RequestParam FoodSafetyEvaluation.Operation operation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(EligibilityResponse.from(evaluations.evaluate(actor(actor),organizationId,lotId,storeId,zoneId,locationId,operation)));
    }
    @PostMapping(value="/lots/{lotId}/safety-status-evidence",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="recordLotSafetyEvidence",summary="Record recall/damage metadata with immutable evidence; existing blocks cannot be cleared by metadata edits")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LotCorrectionResult.class))),
            @ApiResponse(responseCode="200",description="Recorded evidence with current lot state",content=@Content(schema=@Schema(implementation=LotCorrectionResult.class)))})
    public ResponseEntity<LotCorrectionResult> recordSafetyEvidence(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @Valid @RequestBody RecordLotSafetyEvidenceRequest request) {
        var result=lots.recordSafetyEvidence(actor(actor),organizationId,lotId,request.requestId(),
                new LotSafetyEvidence(request.expectedVersion(),request.reason(),request.recallState(),request.damageState(),request.provenance().value()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK)
                .body(new LotCorrectionResult(LotResponse.from(result.lot()),LotCorrectionResponse.from(result.correction())));
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record RecordFoodSafetyPolicyReviewRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Requested rule only; UNVERIFIED reviews cannot normalize dates") PartialDateRule partialDateRule,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceRequest reviewProvenance) { }
    public record RecordLotSafetyEvidenceRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) RecallState recallState,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DamageState damageState,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceRequest provenance) { }
    public record FoodSafetyPolicyVersionResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID profileId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) PartialDateRule partialDateRule,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ApprovalStatus approvalStatus,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceResponse reviewProvenance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID recordedByMembershipId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) {
        static FoodSafetyPolicyVersionResponse from(FoodSafetyPolicyVersion value) {
            return value==null?null:new FoodSafetyPolicyVersionResponse(value.id(),value.profileId(),value.temperatureClass(),value.version(),value.partialDateRule(),
                    value.approvalStatus(),CatalogProvenanceResponse.from(value.reviewProvenance()),value.recordedByMembershipId(),value.recordedAt(),value.reason());
        }
    }
    public record FoodSafetyPolicyHistoryResponse(
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyPolicyVersionResponse current,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<FoodSafetyPolicyVersionResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record EligibilityPolicyReferenceResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID profileId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version) { }
    public record EligibilityEvidenceResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateEvidenceStatus date,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StatusEvidenceStatus recall,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) StatusEvidenceStatus damage,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StorageEvidenceStatus storage,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) PolicyEvidenceStatus policy) { }
    @Schema(description="Point-in-time evidence, not a reusable permission or physical hold. Recompute before future operative use. Without independently verified policy approval the pilot returns DENIED or REQUIRES_REVIEW.")
    public record EligibilityResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Decision decision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ReasonCode> reasonCodes,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyEvaluation.Operation operation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long lotVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long productVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long storeVersion,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID zoneId,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long zoneVersion,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID locationId,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long locationVersion,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) EligibilityPolicyReferenceResponse policy,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant evaluatedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LocalDate storeLocalDate,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String storeTimeZone,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) EligibilityEvidenceResponse evidence,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) NextAction nextAction,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String evaluatorVersion) {
        static EligibilityResponse from(FoodSafetyEvaluation value) {
            var policy=value.policy(); var evidence=value.evidence();
            return new EligibilityResponse(value.decision(),value.reasonCodes(),value.operation(),value.lotId(),value.lotVersion(),value.productId(),value.productVersion(),
                    value.storeId(),value.storeVersion(),value.zoneId(),value.zoneVersion(),value.locationId(),value.locationVersion(),policy==null?null:new EligibilityPolicyReferenceResponse(policy.id(),policy.profileId(),policy.version()),
                    value.evaluatedAt(),value.storeLocalDate(),value.storeTimeZone(),new EligibilityEvidenceResponse(evidence.date(),evidence.recall(),evidence.damage(),evidence.storage(),evidence.policy()),value.nextAction(),value.evaluatorVersion());
        }
    }
}
