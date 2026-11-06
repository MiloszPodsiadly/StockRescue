package com.stockrescue.stores.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.StorageLocation;
import com.stockrescue.stores.application.StorageLocationService;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.stores.domain.LocationData;
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
@RequestMapping(value="/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Invalid metadata or page",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Scoped location, zone or store unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Reserved identity, stale version, changed replay or inactive parent",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class StorageLocationController {
    private final StorageLocationService locations;
    public StorageLocationController(StorageLocationService locations) { this.locations=locations; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="createStorageLocation",summary="Register stable location configuration in an active store and zone with a durable retry key")
    @ApiResponses({@ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=StorageLocationResponse.class))),
            @ApiResponse(responseCode="200",description="Recorded retry with current configuration",content=@Content(schema=@Schema(implementation=StorageLocationResponse.class)))})
    public ResponseEntity<StorageLocationResponse> create(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@Valid @RequestBody CreateStorageLocationRequest request) {
        var result=locations.create(actor(actor),organizationId,storeId,request.requestId(),new LocationData(request.code(),request.name(),request.zoneId()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(StorageLocationResponse.from(result.location()));
    }
    @GetMapping
    @Operation(operationId="listStorageLocations",summary="Read scoped location configuration in stable normalized-code order")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StorageLocationPage.class)))
    public StorageLocationPage list(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(defaultValue="false") boolean includeArchived,@RequestParam(defaultValue="") @Size(max=100) String q,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=locations.list(actor(actor),organizationId,storeId,includeArchived,q,offset,limit);
        return new StorageLocationPage(page.items().stream().map(StorageLocationResponse::from).toList(),page.hasMore(),page.canManage());
    }
    @GetMapping("/{locationId}")
    @Operation(operationId="getStorageLocation",summary="Read retained location identity and whether its current parent configuration is usable")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StorageLocationResponse.class)))
    public StorageLocationResponse detail(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID locationId) {
        return StorageLocationResponse.from(locations.detail(actor(actor),organizationId,storeId,locationId));
    }
    @PostMapping(value="/{locationId}/rename",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="renameStorageLocation",summary="Rename an expected configuration version while preserving code and zone identity")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StorageLocationResponse.class)))
    public StorageLocationResponse rename(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID locationId,@Valid @RequestBody RenameStorageLocationRequest request) {
        return StorageLocationResponse.from(locations.rename(actor(actor),organizationId,storeId,locationId,request.requestId(),request.expectedVersion(),request.name(),request.reason()).location());
    }
    @PostMapping(value="/{locationId}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="archiveStorageLocation",summary="Archive an expected location version, retaining its identity and reserved code")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StorageLocationResponse.class)))
    public StorageLocationResponse archive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID locationId,@Valid @RequestBody ArchiveStorageLocationRequest request) {
        return StorageLocationResponse.from(locations.archive(actor(actor),organizationId,storeId,locationId,request.requestId(),request.expectedVersion(),request.reason()).location());
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record CreateStorageLocationRequest(@NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotBlank @Size(max=32) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String code,
            @NotBlank @Size(max=160) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID zoneId) { }
    public record RenameStorageLocationRequest(@NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=160) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record ArchiveStorageLocationRequest(@NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record StorageLocationPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<StorageLocationResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean canManage) { }
    public record StorageLocationResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID zoneId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Configuration is active with active store and zone; not a physical availability or capacity claim") boolean usable,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant archivedAt) {
        static StorageLocationResponse from(StorageLocation value) {
            return new StorageLocationResponse(value.id(),value.storeId(),value.data().code(),value.data().name(),value.data().zoneId(),value.temperatureClass(),value.active(),value.usable(),value.version(),value.createdAt(),value.updatedAt(),value.archivedAt());
        }
    }
}
