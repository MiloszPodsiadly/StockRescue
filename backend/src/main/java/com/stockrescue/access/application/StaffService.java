package com.stockrescue.access.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.StaffAccess.*;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.persistence.StaffRepository;
import com.stockrescue.access.persistence.InvitationRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {
    private final AccessPolicy access;
    private final StaffRepository staff;
    private final InvitationRepository invitations;
    private final AuditWriter audit;
    public StaffService(AccessPolicy access, StaffRepository staff, InvitationRepository invitations, AuditWriter audit) {
        this.access = access; this.staff = staff; this.invitations = invitations; this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Member> list(UUID actor, UUID org, int offset, int limit, MembershipState state) {
        access.requireAdmin(actor, org, false);
        page(offset, limit);
        return attach(org, staff.members(org, offset, limit, state == null ? "" : state.name())).stream().map(member -> presentation(actor, member)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Member detail(UUID actor, UUID org, UUID member) {
        access.requireAdmin(actor, org, false);
        return presentation(actor, current(org, member, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<StoreOption> stores(UUID actor, UUID org, int offset, int limit) {
        access.requireAdmin(actor, org, false);
        page(offset, limit);
        return staff.stores(org, offset, limit);
    }

    @Transactional
    public Member approve(UUID actor, UUID org, UUID id, UUID verifiedUserId, long version, String reason) {
        access.requireAdmin(actor, org, true);
        var before = current(org, id, true);
        version(before, version);
        reason(reason);
        access.requireMemberAction(actor, before, MemberAction.APPROVE);
        if (before.state() != MembershipState.PENDING || !before.userId().equals(verifiedUserId) || !staff.activeAccount(verifiedUserId)) conflict();
        if (!invitations.claimed(org, before.pendingInvitationId(), verifiedUserId)) conflict();
        var planned = invitations.grants(org, before.pendingInvitationId(), verifiedUserId);
        for (var grant : planned) access.requireStore(actor, org, grant.storeId(), StoreAction.READ_STORE, true);
        staff.approve(org, id);
        for (var grant : planned) staff.grant(org, id, grant.storeId(), grant.role());
        return changed(actor, org, before, "MEMBERSHIP_APPROVED", reason);
    }

    @Transactional
    public Member changeRole(UUID actor, UUID org, UUID id, OrganizationRole role, long version, String reason) {
        access.requireAdmin(actor, org, true);
        var before = current(org, id, true);
        version(before, version);
        reason(reason);
        access.requireMemberAction(actor, before, MemberAction.CHANGE_ROLE);
        if (role == null || before.state() != MembershipState.ACTIVE || before.role() == role || !staff.activeAccount(before.userId())) conflict();
        if (role == OrganizationRole.ORG_ADMIN && before.userId().equals(actor)) throw new AccessDeniedException("Self elevation unavailable");
        if (role == OrganizationRole.MEMBER) preserveAdmin(org, before);
        staff.role(org, id, role);
        return changed(actor, org, before, "MEMBERSHIP_ROLE_CHANGED", reason);
    }

    @Transactional
    public Member revoke(UUID actor, UUID org, UUID id, long version, String reason) {
        var administrator = access.requireAdmin(actor, org, true);
        var before = current(org, id, true);
        version(before, version);
        reason(reason);
        access.requireMemberAction(actor, before, MemberAction.REVOKE);
        if (before.state() == MembershipState.REVOKED) conflict();
        preserveAdmin(org, before);
        staff.revoke(org, id);
        staff.revocation(org, id, false, administrator.membershipId(), reason);
        return changed(actor, org, before, "MEMBERSHIP_REVOKED", reason);
    }

    @Transactional
    public Member grant(UUID actor, UUID org, UUID id, UUID store, StoreRole role, long memberVersion, Long grantVersion, String reason) {
        access.requireAdmin(actor, org, true);
        var before = grantTarget(actor, org, id, memberVersion, reason, MemberAction.SET_GRANT);
        access.requireStore(actor, org, store, StoreAction.READ_STORE, true);
        var existing = before.grants().stream().filter(grant -> grant.storeId().equals(store)).findFirst();
        if (role == null || (existing.isEmpty() ? grantVersion != null : grantVersion == null || existing.orElseThrow().version() != grantVersion)) conflict();
        staff.grant(org, id, store, role);
        staff.touch(org, id);
        return changed(actor, org, before, "STORE_GRANT_CHANGED", reason);
    }

    @Transactional
    public Member revokeGrant(UUID actor, UUID org, UUID id, UUID store, long memberVersion, long grantVersion, String reason) {
        var administrator = access.requireAdmin(actor, org, true);
        var before = grantTarget(actor, org, id, memberVersion, reason, MemberAction.REVOKE_GRANT);
        var existing = before.grants().stream().filter(grant -> grant.storeId().equals(store)).findFirst().orElseThrow(OrganizationUnavailableException::new);
        if (!existing.state().equals("ACTIVE") || existing.version() != grantVersion) conflict();
        var grant = staff.revokeGrant(org, id, store);
        staff.touch(org, id);
        staff.revocation(org, grant, true, administrator.membershipId(), reason);
        return changed(actor, org, before, "STORE_GRANT_REVOKED", reason);
    }

    private Member grantTarget(UUID actor, UUID org, UUID id, long version, String reason, MemberAction action) {
        var before = current(org, id, true);
        version(before, version);
        reason(reason);
        access.requireMemberAction(actor, before, action);
        return before;
    }

    private void preserveAdmin(UUID org, Member before) {
        if (before.role() == OrganizationRole.ORG_ADMIN && before.state() == MembershipState.ACTIVE
                && staff.activeAccount(before.userId()) && staff.activeAdmins(org) <= 1) conflict();
    }

    private Member current(UUID org, UUID id, boolean lock) {
        return attach(org, List.of(staff.member(org, id, lock).orElseThrow(OrganizationUnavailableException::new))).getFirst();
    }

    private List<Member> attach(UUID org, List<Member> members) {
        var grants = staff.grants(org, members.stream().map(Member::id).toList());
        var planned = staff.plannedGrants(org, members.stream().map(Member::id).toList());
        return members.stream().map(member -> new Member(member.id(), member.userId(), member.email(), member.role(), member.state(),
                member.version(), member.pendingInvitationId(), grants.stream().filter(grant -> grant.membershipId().equals(member.id())).map(StaffRepository.GrantRow::grant).toList(),
                planned.stream().filter(grant -> grant.membershipId().equals(member.id())).map(StaffRepository.PlannedGrantRow::grant).toList(), List.of())).toList();
    }

    private Member changed(UUID actor, UUID org, Member before, String action, String reason) {
        var after = current(org, before.id(), false);
        audit.securityChange(actor, org, "membership", before.id(), action,
                Map.of("outcome", "SUCCEEDED", "before", safe(before), "after", safe(after)), reason);
        return presentation(actor, after);
    }

    private Member presentation(UUID actor, Member member) {
        return new Member(member.id(), member.userId(), member.email(), member.role(), member.state(), member.version(), member.pendingInvitationId(), member.grants(), member.plannedGrants(), access.memberActions(actor, member));
    }

    private Map<String, ?> safe(Member member) {
        return Map.of("role", member.role(), "state", member.state(), "version", member.version(), "grants", member.grants(), "plannedGrants", member.plannedGrants());
    }

    private void version(Member member, long version) { if (version < 0 || member.version() != version) conflict(); }
    static void reason(String reason) { if (reason == null || reason.isBlank() || reason.length() > 500 || reason.indexOf('\0') >= 0) throw new AccessFailure(AccessFailure.Kind.INVALID_INPUT); }
    static void page(int offset, int limit) { if (offset < 0 || offset > 10000 || limit < 1 || limit > 101) throw new IllegalArgumentException(); }
    static void conflict() { throw new AccessFailure(AccessFailure.Kind.CONFLICT); }
}
