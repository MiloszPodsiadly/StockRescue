package com.stockrescue.catalog.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.catalog.application.LotData;
import com.stockrescue.catalog.application.LotCorrection;
import com.stockrescue.catalog.application.LotMetadataCorrection;
import com.stockrescue.catalog.application.LotService;
import com.stockrescue.catalog.application.ManufacturerLot;
import com.stockrescue.catalog.domain.LotIdentityState;
import com.stockrescue.catalog.http.CatalogEvidenceDtos.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/lots",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed fields or page",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Catalog action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization, product or lot unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Known identity, changed retry or inactive parent",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid lot or provenance data",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class LotController {
    private final LotService lots;
    public LotController(LotService lots) { this.lots=lots; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="registerManufacturerLot",summary="Register one durable lot identity; candidates never merge by code, and invalid date labels remain recorded evidence")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LotResponse.class))),
            @ApiResponse(responseCode="200",description="Recorded retry; current lot state",content=@Content(schema=@Schema(implementation=LotResponse.class)))})
    public ResponseEntity<LotResponse> register(Authentication actor,@PathVariable UUID organizationId,@Valid @RequestBody RegisterLotRequest request) {
        var data=new LotData(request.productId(),request.lotCode(),request.identityState(),request.manufacturerIdentityKey(),
                request.manufacturerProvenance().value(),request.manufacturerVerified(),request.dateMarking().value());
        var result=lots.register(actor(actor),organizationId,request.requestId(),request.storeId(),data);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(LotResponse.from(result.lot()));
    }
    @GetMapping
    @Operation(operationId="listManufacturerLots",summary="List organization lot identities in stable code/UUID order, optionally for one product")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LotPage.class)))
    public LotPage list(Authentication actor,@PathVariable UUID organizationId,@RequestParam(required=false) UUID productId,
            @RequestParam(defaultValue="false") boolean includeArchived,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=lots.list(actor(actor),organizationId,productId,includeArchived,offset,limit);
        return new LotPage(page.items().stream().map(LotResponse::from).toList(),page.hasMore(),page.canRegister());
    }
    @GetMapping("/{lotId}")
    @Operation(operationId="getManufacturerLot",summary="Read exact date marking and manufacturer evidence, including archived identity")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LotResponse.class)))
    public LotResponse detail(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId) { return LotResponse.from(lots.detail(actor(actor),organizationId,lotId)); }
    @PostMapping(value="/{lotId}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="archiveManufacturerLot",summary="Archive one expected manufacturer-lot version with durable replay and immutable audit evidence")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LotResponse.class))),
            @ApiResponse(responseCode="200",description="Recorded archival command replay",content=@Content(schema=@Schema(implementation=LotResponse.class)))})
    public ResponseEntity<LotResponse> archive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @Valid @RequestBody ArchiveLotRequest request) {
        var result=lots.archive(actor(actor),organizationId,lotId,request.requestId(),request.expectedVersion(),request.reason());
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(LotResponse.from(result.lot()));
    }
    @PostMapping(value="/{lotId}/corrections",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="correctLotMetadata",summary="Review and correct one expected metadata version with immutable evidence and a durable request outcome")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LotCorrectionResult.class))),
            @ApiResponse(responseCode="200",description="Recorded correction with current lot state",content=@Content(schema=@Schema(implementation=LotCorrectionResult.class)))})
    public ResponseEntity<LotCorrectionResult> correct(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @Valid @RequestBody CorrectLotMetadataRequest request) {
        var command=new LotMetadataCorrection(request.expectedVersion(),request.reason(),request.evidenceReviewed(),request.identityState(),
                request.manufacturerIdentityKey(),request.manufacturerProvenance().value(),request.manufacturerVerified(),request.dateMarking().value());
        var result=lots.correct(actor(actor),organizationId,lotId,request.requestId(),command);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK)
                .body(new LotCorrectionResult(LotResponse.from(result.lot()),LotCorrectionResponse.from(result.correction())));
    }
    @GetMapping("/{lotId}/corrections")
    @Operation(operationId="listLotCorrections",summary="Read immutable correction evidence in descending result-version order alongside the current lot version")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LotCorrectionPage.class)))
    public LotCorrectionPage history(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=lots.history(actor(actor),organizationId,lotId,offset,limit);
        return new LotCorrectionPage(page.currentVersion(),page.items().stream().map(LotCorrectionResponse::from).toList(),page.hasMore());
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record ArchiveLotRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record CorrectLotMetadataRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Explicit review of replacement evidence") Boolean evidenceReviewed,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotIdentityState identityState,
            @Size(max=200) String manufacturerIdentityKey,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceRequest manufacturerProvenance,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Boolean manufacturerVerified,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateMarkingRequest dateMarking) { }
    public record LotCorrectionResult(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotResponse lot,
                                      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotCorrectionResponse correction) { }
    public record LotCorrectionPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long currentVersion,
                                    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<LotCorrectionResponse> items,
                                    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record LotCorrectionResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actorUserId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actorMembershipId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long expectedVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long resultVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotCorrection.Kind kind,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotResponse before,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotResponse after) {
        static LotCorrectionResponse from(LotCorrection correction) {
            return new LotCorrectionResponse(correction.id(),correction.lotId(),correction.actorUserId(),correction.actorMembershipId(),
                    correction.expectedVersion(),correction.resultVersion(),correction.reason(),correction.recordedAt(),correction.kind(),
                    LotResponse.from(correction.before()),LotResponse.from(correction.after()));
        }
    }
    public record RegisterLotRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(types={"string","null"},format="uuid",description="Optional active store context; required for store personnel") UUID storeId,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @NotBlank @Size(max=128) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String lotCode,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotIdentityState identityState,
            @Size(max=200) String manufacturerIdentityKey,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceRequest manufacturerProvenance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Explicit manufacturer identity review; never food-safety policy approval") boolean manufacturerVerified,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateMarkingRequest dateMarking) { }
    public record LotPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<LotResponse> items,
                          @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,
                          @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean canRegister) { }
    public record LotResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String lotCode,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LotIdentityState identityState,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String manufacturerIdentityKey,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceResponse manufacturerProvenance,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID confirmedByMembershipId,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant confirmedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateMarkingResponse dateMarking,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ManufacturerLot.RecallState recallState,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ManufacturerLot.DamageState damageState,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceResponse statusProvenance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant archivedAt) {
        static LotResponse from(ManufacturerLot lot) {
            var data=lot.data();
            return new LotResponse(lot.id(),data.productId(),data.lotCode(),data.identityState(),data.manufacturerIdentityKey(),
                    CatalogProvenanceResponse.from(data.manufacturerProvenance()),lot.confirmedByMembershipId(),lot.confirmedAt(),
                    DateMarkingResponse.from(data.dateMarking()),lot.recallState(),lot.damageState(),CatalogProvenanceResponse.from(lot.statusProvenance()),
                    lot.active(),lot.version(),lot.createdAt(),lot.updatedAt(),lot.archivedAt());
        }
    }
}
