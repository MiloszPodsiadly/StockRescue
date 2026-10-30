package com.stockrescue.stores.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.LaneService;
import com.stockrescue.stores.application.OperationalConfiguration.*;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/transport-lanes",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Invalid fields, currency or decimal precision",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Org Admin or CSRF required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Scoped resource unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Stale version, invalid revision order or incompatible/inactive configuration",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class LaneController {
    private final LaneService lanes;
    public LaneController(LaneService lanes) { this.lanes=lanes; }
    @GetMapping
    @Operation(operationId="listTransportLanes",summary="Admin reads scoped configured routes and explicit missing configuration")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LanePageResponse.class)))
    public LanePageResponse list(Authentication actor,@PathVariable UUID organizationId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=lanes.list(actor(actor),organizationId,offset,limit);
        return new LanePageResponse(page.items().stream().map(LaneResponse::from).toList(),page.hasMore(),page.asOf());
    }
    @GetMapping("/lookup")
    @Operation(operationId="lookupTransportLane",summary="Report a missing directed route as UNCONFIGURED, never a zero-price route")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LaneLookupResponse.class)))
    public LaneLookupResponse lookup(Authentication actor,@PathVariable UUID organizationId,@RequestParam UUID originStoreId,@RequestParam UUID destinationStoreId) {
        var result=lanes.lookup(actor(actor),organizationId,originStoreId,destinationStoreId);
        return result.lane()==null?new LaneLookupResponse(Status.UNCONFIGURED,List.of(Missing.NO_LANE),null,result.asOf())
                :new LaneLookupResponse(result.lane().status(),result.lane().missing(),LaneResponse.from(result.lane()),result.asOf());
    }
    @GetMapping("/currencies")
    @Operation(operationId="supportedLaneCurrencies",summary="ISO country currencies recognized by the current JDK; no historic/fund/test-unit pricing")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=CurrencyOptions.class)))
    public CurrencyOptions currencies(Authentication actor,@PathVariable UUID organizationId) { return new CurrencyOptions(lanes.currencies(actor(actor),organizationId)); }
    @GetMapping("/{laneId}")
    @Operation(operationId="getTransportLane",summary="Read a lane's current configuration state and effective cost, if available")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LaneResponse.class)))
    public LaneResponse detail(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID laneId) { return LaneResponse.from(lanes.detail(actor(actor),organizationId,laneId)); }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="createTransportLane",summary="Configure one ordered store pair through compatible active zones; cost starts unconfigured")
    @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LaneResponse.class)))
    public LaneResponse create(Authentication actor,@PathVariable UUID organizationId,@Valid @RequestBody CreateLaneRequest request) {
        return LaneResponse.from(lanes.create(actor(actor),organizationId,request.originStoreId(),request.destinationStoreId(),request.originZoneId(),request.destinationZoneId(),request.reason()));
    }
    @PatchMapping(value="/{laneId}",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="updateTransportLane",summary="Update declared zones or disable a lane at its current version; store endpoints stay fixed")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=LaneResponse.class)))
    public LaneResponse update(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID laneId,@Valid @RequestBody UpdateLaneRequest request) {
        return LaneResponse.from(lanes.update(actor(actor),organizationId,laneId,request.originZoneId(),request.destinationZoneId(),request.active(),request.version(),request.reason()));
    }
    @PostMapping(value="/{laneId}/revisions",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="appendLaneCostRevision",summary="Approve an immutable decimal cost/duration version; omitted effectiveAt means server approval time")
    @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=LaneResponse.class)))
    public LaneResponse revise(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID laneId,@Valid @RequestBody CostRevisionRequest request) {
        return LaneResponse.from(lanes.revise(actor(actor),organizationId,laneId,request.laneVersion(),request.expectedRevision(),request.costAmount(),request.currencyCode(),request.durationMinutes(),request.effectiveAt(),request.reason()));
    }
    @GetMapping("/{laneId}/revisions")
    @Operation(operationId="laneCostHistory",summary="Immutable price evidence; validity intervals end at the next revision's effective time")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=RevisionPageResponse.class)))
    public RevisionPageResponse history(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID laneId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=lanes.history(actor(actor),organizationId,laneId,offset,limit);
        return new RevisionPageResponse(page.items().stream().map(RevisionResponse::from).toList(),page.hasMore());
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record CreateLaneRequest(@NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID originStoreId,
                                     @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
                                     @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID originZoneId,
                                     @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID destinationZoneId,
                                     @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record UpdateLaneRequest(UUID originZoneId,UUID destinationZoneId,
                                     @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Boolean active,
                                     @NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long version,
                                     @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record CostRevisionRequest(@NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long laneVersion,
                                       @NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedRevision,
                                       @NotBlank @Size(max=20) @Pattern(regexp="(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?") @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String costAmount,
                                       @NotBlank @Pattern(regexp="[A-Z]{3}") @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String currencyCode,
                                       @NotNull @Min(1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Integer durationMinutes,
                                       Instant effectiveAt,@NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record CurrencyOptions(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> codes) { }
    public record RevisionResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
                                     @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String costAmount,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String currencyCode,
                                     @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int durationMinutes,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant effectiveAt,
                                     @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant validUntil,
                                     @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID recordedByMembershipId,
                                     @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID originZoneId,
                                     @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID destinationZoneId,
                                     @Schema(nullable=true,requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass) {
        static RevisionResponse from(Revision revision) { return revision==null?null:new RevisionResponse(revision.id(),revision.revision(),revision.costAmount(),revision.currencyCode(),revision.durationMinutes(),revision.effectiveAt(),revision.validUntil(),revision.recordedAt(),revision.recordedByMembershipId(),revision.originZoneId(),revision.destinationZoneId(),revision.temperatureClass()); }
    }
    public record LaneResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID originStoreId,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String originStoreName,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String destinationStoreName,
                                 @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID originZoneId,
                                 @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID destinationZoneId,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long latestRevision,
                                 @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant latestEffectiveAt,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Status status,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Missing> missing,
                                 @Schema(nullable=true,requiredMode=Schema.RequiredMode.REQUIRED) RevisionResponse currentCost,
                                 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant asOf) {
        static LaneResponse from(LaneView view) { var lane=view.lane();return new LaneResponse(lane.id(),lane.originStoreId(),lane.originStoreName(),lane.destinationStoreId(),lane.destinationStoreName(),lane.originZoneId(),lane.destinationZoneId(),lane.active(),lane.version(),lane.latestRevision(),lane.latestEffectiveAt(),view.status(),view.missing(),RevisionResponse.from(view.currentCost()),view.asOf()); }
    }
    public record LanePageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<LaneResponse> items,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant asOf) { }
    public record LaneLookupResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Status status,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Missing> missing,
                                      @Schema(nullable=true,requiredMode=Schema.RequiredMode.REQUIRED) LaneResponse lane,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant asOf) { }
    public record RevisionPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<RevisionResponse> items,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
}
