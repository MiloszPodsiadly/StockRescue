package com.stockrescue.stores.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.StoreService;
import com.stockrescue.stores.domain.StoreData;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1/organizations/{organizationId}/stores", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "session")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid store data, timezone or page", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "Action or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "404", description = "Organization or store unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "409", description = "Reserved code, changed retry, stale version or inactive store", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "500", description = "Request failed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class StoreController {
    private final StoreService stores;
    public StoreController(StoreService stores) { this.stores = stores; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createStore", summary = "Org Admin creates one durable store with an actor/tenant-scoped retry key")
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = StoreResponse.class))),
            @ApiResponse(responseCode = "200", description = "Unchanged creation retry; current store state returned", content = @Content(schema = @Schema(implementation = StoreResponse.class)))
    })
    public ResponseEntity<StoreResponse> create(Authentication actor, @PathVariable UUID organizationId, @Valid @RequestBody CreateStoreRequest request) {
        var result = stores.create(actor(actor), organizationId, request.requestId(), new StoreData(request.code(), request.name(), request.addressLine1(), request.city(), request.postalCode(), request.countryCode(), request.zoneId()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(StoreResponse.from(result.view()));
    }
    @GetMapping
    @Operation(operationId = "listStores", summary = "List only current actor scope with fixed code ordering and no global totals")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StorePage.class)))
    public StorePage list(Authentication actor, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "ACTIVE") StoreService.State state,
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        var page = stores.list(actor(actor), organizationId, state, q, offset, limit);
        return new StorePage(page.items().stream().map(StoreResponse::from).toList(), page.hasMore(), page.canCreate());
    }
    @GetMapping("/{storeId}")
    @Operation(operationId = "getStore", summary = "Read an assigned active store; Org Admin can also read archived history")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StoreResponse.class)))
    public StoreResponse detail(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID storeId) {
        return StoreResponse.from(stores.detail(actor(actor), organizationId, storeId));
    }
    @PatchMapping(value = "/{storeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateStore", summary = "Admin or assigned Store Manager updates metadata at the current version; code is immutable")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StoreResponse.class)))
    public StoreResponse update(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID storeId, @Valid @RequestBody UpdateStoreRequest request) {
        return StoreResponse.from(stores.update(actor(actor), organizationId, storeId, request.name(), request.addressLine1(), request.city(), request.postalCode(), request.countryCode(), request.zoneId(), request.version(), request.reason()));
    }
    @PostMapping(value = "/{storeId}/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "archiveStore", summary = "Org Admin deactivates a store without deleting its identity, code or history")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StoreResponse.class)))
    public StoreResponse archive(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID storeId, @Valid @RequestBody ArchiveStoreRequest request) {
        return StoreResponse.from(stores.archive(actor(actor), organizationId, storeId, request.version(), request.reason()));
    }
    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record CreateStoreRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
                                     @NotBlank @Size(max = 32) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                                     @NotBlank @Size(max = 160) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                                     @Size(max = 200) String addressLine1, @Size(max = 100) String city,
                                     @Size(max = 20) String postalCode, @Size(max = 2) String countryCode,
                                     @NotBlank @Size(max = 100) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String zoneId) { }
    public record UpdateStoreRequest(@NotBlank @Size(max = 160) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                                     @Size(max = 200) String addressLine1, @Size(max = 100) String city,
                                     @Size(max = 20) String postalCode, @Size(max = 2) String countryCode,
                                     @NotBlank @Size(max = 100) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String zoneId,
                                     @NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long version,
                                     @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record ArchiveStoreRequest(@NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long version,
                                      @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record StorePage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreResponse> items,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canCreate) { }
    public record StoreResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                                @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String addressLine1,
                                @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String city,
                                @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String postalCode,
                                @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String countryCode,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String zoneId,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreAction> allowedActions) {
        static StoreResponse from(StoreService.View view) {
            var store = view.store();
            var data = store.data();
            return new StoreResponse(store.id(), data.code(), data.name(), data.addressLine1(), data.city(), data.postalCode(), data.countryCode(), data.zoneId(), store.active(), store.version(), store.createdAt(), store.updatedAt(), view.allowedActions());
        }
    }
}
