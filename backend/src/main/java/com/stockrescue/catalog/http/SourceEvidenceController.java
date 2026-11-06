package com.stockrescue.catalog.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.catalog.application.*;
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
@RequestMapping(value="/api/v1/organizations/{organizationId}/lots/{lotId}/source-evidence",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed fields or page",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Source registration or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization, store, lot or source unavailable in current scope",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Changed retry or archived parent",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid source metadata",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class SourceEvidenceController {
    private final SourceEvidenceService sources;
    public SourceEvidenceController(SourceEvidenceService sources) { this.sources=sources; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="registerSourceEvidence",summary="Record independent source metadata with a durable retry key; this is not a physical receipt or quantity")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=SourceEvidenceResponse.class))),
            @ApiResponse(responseCode="200",description="Recorded source replayed in current actor scope",content=@Content(schema=@Schema(implementation=SourceEvidenceResponse.class)))})
    public ResponseEntity<SourceEvidenceResponse> register(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,@Valid @RequestBody RegisterSourceEvidenceRequest request) {
        var result=sources.register(actor(actor),organizationId,lotId,request.requestId(),new SourceEvidenceData(request.storeId(),request.supplierName(),request.supplierReference(),request.provenance().value()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(SourceEvidenceResponse.from(result.evidence()));
    }
    @GetMapping
    @Operation(operationId="traceLotSources",summary="Read recorded source trace in current administrator/store scope; it does not establish exact per-unit origin")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=SourceTracePage.class)))
    public SourceTracePage trace(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var trace=sources.trace(actor(actor),organizationId,lotId,offset,limit);
        return new SourceTracePage(trace.lotId(),trace.items().stream().map(SourceEvidenceResponse::from).toList(),trace.hasMore(),trace.visibilityScope(),trace.coverage());
    }
    @GetMapping("/{evidenceId}")
    @Operation(operationId="getSourceEvidence",summary="Read one independent source record only when its store/organization scope remains authorized")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=SourceEvidenceResponse.class)))
    public SourceEvidenceResponse detail(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID lotId,@PathVariable UUID evidenceId) {
        return SourceEvidenceResponse.from(sources.detail(actor(actor),organizationId,lotId,evidenceId));
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record RegisterSourceEvidenceRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            UUID storeId,@Size(max=160) String supplierName,@Size(max=256) String supplierReference,
            @NotNull @Valid @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceRequest provenance) { }
    public record SourceTracePage(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<SourceEvidenceResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) SourceEvidenceService.VisibilityScope visibilityScope,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) SourceEvidenceService.Coverage coverage) { }
    public record SourceEvidenceResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String supplierName,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String supplierReference,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceResponse provenance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant archivedAt) {
        static SourceEvidenceResponse from(ConsignmentEvidence evidence) {
            var data=evidence.data();
            return new SourceEvidenceResponse(evidence.id(),evidence.lotId(),data.storeId(),data.supplierName(),data.supplierReference(),
                    CatalogProvenanceResponse.from(data.provenance()),evidence.active(),evidence.version(),evidence.createdAt(),evidence.updatedAt(),evidence.archivedAt());
        }
    }
}
