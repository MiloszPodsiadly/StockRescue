package com.stockrescue.access.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;

public final class StaffAccess {
    private StaffAccess() { }
    public enum MembershipState { ACTIVE, PENDING, REVOKED }
    public enum MemberAction { APPROVE, CHANGE_ROLE, REVOKE, SET_GRANT, REVOKE_GRANT }
    public record Member(UUID id, UUID userId, String email, OrganizationRole role, MembershipState state,
                         long version, UUID pendingInvitationId, List<Grant> grants, List<PlannedGrant> plannedGrants, List<MemberAction> allowedActions) { }
    public record Grant(UUID storeId, StoreRole role, String state, long version) { }
    public record PlannedGrant(UUID storeId, StoreRole role) { }
    public record StoreOption(UUID id, String code, String name) { }
    public record Invitation(UUID id, String email, String status, Instant expiresAt, long version) { }
    public record IssuedInvitation(UUID id, String token, Instant expiresAt) {
        @Override public String toString() { return "IssuedInvitation[redacted]"; }
    }
    public record Claim(UUID organizationId, UUID membershipId) { }
}
