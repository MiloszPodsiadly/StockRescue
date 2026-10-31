package com.stockrescue.access.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.access.application.*;
import com.stockrescue.access.application.StaffAccess.*;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "session")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "CSRF or action denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "404", description = "Resource or invitation unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "409", description = "Stale version, invalid transition, duplicate invitation or last administrator", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "429", description = "Durable per-account invitation rate limit", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "500", description = "Request failed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class StaffController {
    private final StaffService staff;
    private final InvitationService invitations;
    public StaffController(StaffService staff, InvitationService invitations) { this.staff = staff; this.invitations = invitations; }

    @GetMapping("/organizations/{organizationId}/members")
    @Operation(operationId = "listMembers", summary = "Org Admin lists scoped memberships, including pending and revoked history")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberPage.class)))
    public MemberPage members(Authentication actor, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) MembershipState state) {
        var rows = staff.list(actor(actor), organizationId, offset, limit + 1, state);
        return new MemberPage(rows.stream().limit(limit).map(MemberResponse::from).toList(), rows.size() > limit);
    }

    @GetMapping("/organizations/{organizationId}/members/{membershipId}")
    @Operation(operationId = "getMember", summary = "Org Admin reads one scoped membership and its explicit grants")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse member(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId) {
        return MemberResponse.from(staff.detail(actor(actor), organizationId, membershipId));
    }

    @PostMapping(value = "/organizations/{organizationId}/members/{membershipId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveMembership", summary = "Activate a claimed membership only after administrator verification of the person and account reference")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse approve(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId, @Valid @RequestBody ApprovalRequest request) {
        return MemberResponse.from(staff.approve(actor(actor), organizationId, membershipId, request.verifiedUserId(), request.version(), request.reason()));
    }

    @PatchMapping(value = "/organizations/{organizationId}/members/{membershipId}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeMemberRole", summary = "Org Admin changes an active member's role with version and last-admin protection")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse role(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId, @Valid @RequestBody RoleRequest request) {
        return MemberResponse.from(staff.changeRole(actor(actor), organizationId, membershipId, request.role(), request.version(), request.reason()));
    }

    @DeleteMapping(value = "/organizations/{organizationId}/members/{membershipId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeMembership", summary = "Revoke membership and all current store grants while retaining history")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse revoke(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId, @Valid @RequestBody VersionedReason request) {
        return MemberResponse.from(staff.revoke(actor(actor), organizationId, membershipId, request.version(), request.reason()));
    }

    @PutMapping(value = "/organizations/{organizationId}/members/{membershipId}/store-grants/{storeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setStoreGrant", summary = "Org Admin grants one store role to another active member; no self grant")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse grant(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId, @PathVariable UUID storeId, @Valid @RequestBody GrantRequest request) {
        return MemberResponse.from(staff.grant(actor(actor), organizationId, membershipId, storeId, request.role(), request.memberVersion(), request.grantVersion(), request.reason()));
    }

    @DeleteMapping(value = "/organizations/{organizationId}/members/{membershipId}/store-grants/{storeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeStoreGrant", summary = "Org Admin revokes a specific store grant with current member and grant versions")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemberResponse.class)))
    public MemberResponse revokeGrant(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID membershipId, @PathVariable UUID storeId, @Valid @RequestBody GrantRevocationRequest request) {
        return MemberResponse.from(staff.revokeGrant(actor(actor), organizationId, membershipId, storeId, request.memberVersion(), request.grantVersion(), request.reason()));
    }

    @GetMapping("/organizations/{organizationId}/access-stores")
    @Operation(operationId = "listAccessStores", summary = "Org Admin selects only active stores in this organization for explicit grants")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StoreOptionPage.class)))
    public StoreOptionPage stores(Authentication actor, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        var rows = staff.stores(actor(actor), organizationId, offset, limit + 1);
        return new StoreOptionPage(rows.stream().limit(limit).map(store -> new StoreOptionResponse(store.id(), store.code(), store.name())).toList(), rows.size() > limit);
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    @Operation(operationId = "listInvitations", summary = "Org Admin lists scoped invitation history without secrets")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = InvitationPage.class)))
    public InvitationPage invitations(Authentication actor, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        var rows = invitations.list(actor(actor), organizationId, offset, limit + 1);
        return new InvitationPage(rows.stream().limit(limit).map(invite -> new InvitationResponse(invite.id(), invite.email(), invite.status(), invite.expiresAt(), invite.version())).toList(), rows.size() > limit);
    }

    @PostMapping(value = "/organizations/{organizationId}/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "inviteStaff", summary = "Create a 24-hour invite; return its secret once for manual secure sharing, without sending email")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = InvitationIssuedResponse.class)))
    public InvitationIssuedResponse invite(Authentication actor, @PathVariable UUID organizationId, @Valid @RequestBody InviteRequest request) {
        var invite = invitations.invite(actor(actor), organizationId, request.email(), request.storeGrants().stream().map(grant -> new PlannedGrant(grant.storeId(), grant.role())).toList(), request.reason());
        return new InvitationIssuedResponse(invite.id(), invite.token(), invite.expiresAt());
    }

    @DeleteMapping(value = "/organizations/{organizationId}/invitations/{invitationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeInvitation", summary = "Revoke a pending invitation without deleting its history")
    @ApiResponse(responseCode = "204", content = @Content)
    public void revokeInvitation(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID invitationId, @Valid @RequestBody VersionedReason request) {
        invitations.revoke(actor(actor), organizationId, invitationId, request.version(), request.reason());
    }

    @PostMapping(value = "/invitations/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "acceptInvitation", summary = "Consume the recipient-bound secret once; request manual review without activating any authority")
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = InvitationClaimResponse.class)))
    public InvitationClaimResponse accept(Authentication actor, @Valid @RequestBody AcceptInvitationRequest request) {
        var claim = invitations.accept(actor(actor), request.token());
        return new InvitationClaimResponse(claim.organizationId(), claim.membershipId(), "PENDING_APPROVAL");
    }

    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record VersionedReason(@NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long version,
                                  @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record RoleRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrganizationRole role,
                              @NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long version,
                              @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record ApprovalRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID verifiedUserId,
                                  @NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long version,
                                  @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record GrantRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StoreRole role,
                               @NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long memberVersion, @Min(0) Long grantVersion,
                               @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record GrantRevocationRequest(@NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long memberVersion,
                                         @NotNull @Min(0) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long grantVersion,
                                         @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record PlannedGrantRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId,
                                      @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StoreRole role) { }
    public record InviteRequest(@NotBlank @Email @Size(max = 254) @Pattern(regexp = "[!-~]+") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
                                @NotNull @Size(max = 50) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<@NotNull @Valid PlannedGrantRequest> storeGrants,
                                @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) { }
    public record AcceptInvitationRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token) {
        @Override public String toString() { return "AcceptInvitationRequest[redacted]"; }
    }
    public record MemberResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrganizationRole role,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MembershipState state,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GrantResponse> grants,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PlannedGrantResponse> plannedGrants,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MemberAction> allowedActions) {
        static MemberResponse from(Member member) { return new MemberResponse(member.id(), member.userId(), member.email(), member.role(), member.state(), member.version(), member.grants().stream().map(grant -> new GrantResponse(grant.storeId(), grant.role(), grant.state(), grant.version())).toList(), member.plannedGrants().stream().map(grant -> new PlannedGrantResponse(grant.storeId(), grant.role())).toList(), member.allowedActions()); }
    }
    public record PlannedGrantResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StoreRole role) { }
    public record GrantResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StoreRole role,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) { }
    public record MemberPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MemberResponse> items, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record StoreOptionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) { }
    public record StoreOptionPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreOptionResponse> items, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record InvitationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) { }
    public record InvitationPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<InvitationResponse> items, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record InvitationIssuedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
        @Override public String toString() { return "InvitationIssuedResponse[redacted]"; }
    }
    public record InvitationClaimResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID organizationId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID membershipId,
                                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) { }
}
