package com.stockrescue.stores.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.ZoneService;
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
@RequestMapping(value="/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Invalid fields",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Scoped resource unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Duplicate, stale version, changed archive impact or inactive zone",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class ZoneController {
    private final ZoneService zones;
    public ZoneController(ZoneService zones) { this.zones=zones; }
    @GetMapping
    @Operation(operationId="listStorageZones",summary="Read declared temperature compatibility in the current store scope; no capacity or availability assertion")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ZonePageResponse.class)))
    public ZonePageResponse list(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(defaultValue="false") boolean includeInactive,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=zones.list(actor(actor),organizationId,storeId,includeInactive,offset,limit);
        return new ZonePageResponse(page.items().stream().map(ZoneResponse::from).toList(),page.hasMore(),page.canManage());
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="createStorageZone",summary="Admin or assigned Store Manager declares a storage zone with fixed temperature class")
    @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=ZoneResponse.class)))
    public ZoneResponse create(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@Valid @RequestBody CreateZoneRequest request) {
        return ZoneResponse.from(zones.create(actor(actor),organizationId,storeId,request.name(),request.temperatureClass()));
    }
    @PatchMapping(value="/{zoneId}",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="renameStorageZone",summary="Rename a current active zone without changing its identity or temperature class")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ZoneResponse.class)))
    public ZoneResponse rename(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID zoneId,@Valid @RequestBody RenameZoneRequest request) {
        return ZoneResponse.from(zones.rename(actor(actor),organizationId,storeId,zoneId,request.name(),request.version(),request.reason()));
    }
    @GetMapping("/{zoneId}/archive-impact")
    @Operation(operationId="previewStorageZoneArchiveImpact",summary="Preview scoped active-lane and future-schedule counts before irreversible zone archival")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ZoneArchiveImpactResponse.class)))
    public ZoneArchiveImpactResponse archiveImpact(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID zoneId) {
        return ZoneArchiveImpactResponse.from(zones.archiveImpact(actor(actor),organizationId,storeId,zoneId));
    }
    @PostMapping(value="/{zoneId}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="archiveStorageZone",summary="Permanently deactivate a zone after confirming its current operational impact")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ZoneResponse.class)))
    public ZoneResponse archive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID zoneId,@Valid @RequestBody ArchiveZoneRequest request) {
        return ZoneResponse.from(zones.archive(actor(actor),organizationId,storeId,zoneId,request.version(),request.reason(),request.impactFingerprint()));
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record CreateZoneRequest(@NotBlank @Size(max=100) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
                                     @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass) { }
    public record RenameZoneRequest(@NotBlank @Size(max=100) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
                                     @NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long version,
                                     @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record ArchiveZoneRequest(@NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long version,
                                     @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
                                     @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Opaque impact snapshot returned by the scoped preview endpoint.") String impactFingerprint) { }
    public record ZoneArchiveImpactResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) int activeLaneCount,
                                            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int inactiveLaneCount,
                                            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int futureScheduledRevisionCount,
                                            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long zoneVersion,
                                            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Opaque scoped snapshot; send unchanged when confirming archival.") String impactFingerprint,
                                            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="PostgreSQL wall-clock instant used to count future active revisions.") Instant asOf) {
        static ZoneArchiveImpactResponse from(ZoneArchiveImpact impact) { return new ZoneArchiveImpactResponse(impact.activeLaneCount(),impact.inactiveLaneCount(),impact.futureScheduledRevisionCount(),impact.zoneVersion(),impact.impactFingerprint(),impact.asOf()); }
    }
    public record ZoneResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
                                @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant updatedAt) {
        static ZoneResponse from(Zone zone) { return new ZoneResponse(zone.id(),zone.name(),zone.temperatureClass(),zone.active(),zone.version(),zone.createdAt(),zone.updatedAt()); }
    }
    public record ZonePageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ZoneResponse> items,
                                    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,
                                    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean canManage) { }
}
