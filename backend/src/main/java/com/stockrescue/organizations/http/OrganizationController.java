package com.stockrescue.organizations.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import com.stockrescue.access.application.OrganizationAccess;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.organizations.application.OrganizationService;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.security.WebSessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "session")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "CSRF invalid or no organization selected", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "404", description = "Organization unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "409", description = "Onboarding already completed or retry payload changed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "500", description = "Request could not be completed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "503", description = "Session storage temporarily unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class OrganizationController {
    private final OrganizationService organizations;
    private final AccessPolicy access;
    private final WebSessions sessions;

    public OrganizationController(OrganizationService organizations, AccessPolicy access, WebSessions sessions) {
        this.organizations = organizations;
        this.access = access;
        this.sessions = sessions;
    }

    @PostMapping(value = "/organizations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "onboardOrganization", summary = "Create the actor's first owned organization and its initial Org Admin atomically")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Organization and initial ownership created", content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
            @ApiResponse(responseCode = "200", description = "Unchanged retry of completed onboarding", content = @Content(schema = @Schema(implementation = OrganizationResponse.class)))
    })
    public ResponseEntity<OrganizationResponse> onboard(Authentication actor, @Valid @RequestBody OnboardingRequest request) {
        var result = organizations.onboard(actor(actor), request.requestId(), request.name());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(OrganizationResponse.from(result.organization()));
    }

    @GetMapping("/organizations")
    @Operation(operationId = "listOrganizations", summary = "List only current, active actor memberships without global totals")
    @ApiResponse(responseCode = "200", description = "Actor-scoped organization page", content = @Content(schema = @Schema(implementation = OrganizationListResponse.class)))
    public OrganizationListResponse list(Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        var rows = organizations.list(actor(actor), offset, limit + 1);
        return new OrganizationListResponse(rows.stream().limit(limit).map(OrganizationResponse::from).toList(), offset, limit, rows.size() > limit);
    }

    @GetMapping("/organizations/{organizationId}")
    @Operation(operationId = "getOrganization", summary = "Read an organization through the actor's current membership")
    @ApiResponse(responseCode = "200", description = "Authorized organization", content = @Content(schema = @Schema(implementation = OrganizationResponse.class)))
    public OrganizationResponse detail(Authentication actor, @PathVariable UUID organizationId) {
        return OrganizationResponse.from(organizations.detail(actor(actor), organizationId));
    }

    @GetMapping("/auth/active-organization")
    @Operation(operationId = "getActiveOrganization", summary = "Resolve the session selection through current membership; discard stale selectors")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current authorized selection", content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
            @ApiResponse(responseCode = "204", description = "No current authorized selection", content = @Content)
    })
    public ResponseEntity<OrganizationResponse> selected(Authentication actor, HttpServletRequest request) {
        var selected = sessions.selectedOrganization(request);
        if (selected == null) return ResponseEntity.noContent().build();
        try {
            return ResponseEntity.ok(OrganizationResponse.from(organizations.detail(actor(actor), selected)));
        } catch (OrganizationUnavailableException unavailable) {
            sessions.clearOrganization(request);
            return ResponseEntity.noContent().build();
        }
    }

    @PostMapping(value = "/auth/active-organization", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "selectOrganization", summary = "Select a currently authorized organization; this selection grants no authority")
    @ApiResponse(responseCode = "200", description = "Validated selection", content = @Content(schema = @Schema(implementation = OrganizationResponse.class)))
    public OrganizationResponse select(Authentication actor, @Valid @RequestBody SelectionRequest selection, HttpServletRequest request) {
        var organization = organizations.detail(actor(actor), selection.organizationId());
        sessions.selectOrganization(request, organization.id());
        return OrganizationResponse.from(organization);
    }

    @GetMapping("/auth/me/effective-permissions")
    @Operation(operationId = "effectivePermissions", summary = "Resolve current membership and store grants for the session selection")
    @ApiResponse(responseCode = "200", description = "Current persisted permissions for UX; every command still checks authority", content = @Content(schema = @Schema(implementation = PermissionsResponse.class)))
    public PermissionsResponse permissions(Authentication actor, HttpServletRequest request) {
        var selected = sessions.selectedOrganization(request);
        if (selected == null) throw new OrganizationSelectionRequiredException();
        try {
            var permissions = access.permissions(actor(actor), selected);
            return new PermissionsResponse(OrganizationResponse.from(permissions.organization()), permissions.organizationActions(),
                    permissions.allStoreActions(), permissions.storeGrants().stream()
                            .map(grant -> new StorePermissionResponse(grant.storeId(), grant.role(), grant.actions())).toList());
        } catch (OrganizationUnavailableException unavailable) {
            sessions.clearOrganization(request);
            throw unavailable;
        }
    }

    private UUID actor(Authentication authentication) { return UUID.fromString(authentication.getName()); }

    public record OnboardingRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
                                   @NotBlank @Size(max = 160) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) { }
    public record SelectionRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID organizationId) { }
    public record OrganizationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID membershipId,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrganizationRole role) {
        static OrganizationResponse from(OrganizationAccess organization) {
            return new OrganizationResponse(organization.id(), organization.name(), organization.createdAt(), organization.membershipId(), organization.role());
        }
    }
    public record OrganizationListResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrganizationResponse> items,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int offset,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int limit,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record PermissionsResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrganizationResponse organization,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrganizationAction> organizationActions,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreAction> allStoreActions,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StorePermissionResponse> storeGrants) { }
    public record StorePermissionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId,
                                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StoreRole role,
                                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreAction> actions) { }
}
