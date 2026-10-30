package com.stockrescue.access.application;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.StaffAccess.*;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.persistence.AccessRepository;
import com.stockrescue.access.persistence.InvitationRepository;
import com.stockrescue.access.persistence.StaffRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    private final AccessPolicy access;
    private final InvitationRepository invitations;
    private final StaffRepository staff;
    private final AccessRepository authority;
    private final InvitationTokens tokens;
    private final InvitationRateLimit limits;
    private final AuditWriter audit;
    private final Clock clock;
    public InvitationService(AccessPolicy access, InvitationRepository invitations, StaffRepository staff, AccessRepository authority,
            InvitationTokens tokens, InvitationRateLimit limits, AuditWriter audit, Clock clock) {
        this.access = access; this.invitations = invitations; this.staff = staff; this.authority = authority;
        this.tokens = tokens; this.limits = limits; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Invitation> list(UUID actor, UUID org, int offset, int limit) {
        access.requireAdmin(actor, org, false);
        StaffService.page(offset, limit);
        return invitations.list(org, offset, limit);
    }

    @Transactional
    public IssuedInvitation invite(UUID actor, UUID org, String email, List<PlannedGrant> grants, String reason) {
        if (!limits.admit(actor, "INVITE")) throw new AccessFailure(AccessFailure.Kind.RATE_LIMITED);
        var administrator = access.requireAdmin(actor, org, true);
        StaffService.reason(reason);
        var normalized = email.strip().toLowerCase(Locale.ROOT);
        if (grants.size() > 50 || new HashSet<>(grants.stream().map(PlannedGrant::storeId).toList()).size() != grants.size()) StaffService.conflict();
        if (invitations.existingRecipient(org, normalized) || invitations.pending(org, normalized)) StaffService.conflict();
        for (var expired : invitations.expire(org)) audit.securityChange(actor, org, "invitation", expired, "INVITATION_EXPIRED",
                Map.of("outcome", "SUCCEEDED", "before", "PENDING", "after", "EXPIRED"), "Invitation validity elapsed");
        for (var grant : grants.stream().sorted(java.util.Comparator.comparing(PlannedGrant::storeId)).toList())
            access.requireStore(actor, org, grant.storeId(), StoreAction.READ_STORE, true);
        var id = UUID.randomUUID();
        var secret = tokens.generate();
        var expiry = clock.instant().plus(Duration.ofHours(24));
        invitations.create(id, org, normalized, tokens.digest(secret), administrator.membershipId(), expiry, grants);
        audit.securityChange(actor, org, "invitation", id, "INVITATION_CREATED", Map.of("outcome", "SUCCEEDED", "after", "PENDING", "plannedGrants", grants), reason);
        return new IssuedInvitation(id, secret, expiry);
    }

    @Transactional
    public Claim accept(UUID actor, String token) {
        if (!limits.admit(actor, "REDEEM")) throw new AccessFailure(AccessFailure.Kind.RATE_LIMITED);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new AccessFailure(AccessFailure.Kind.INVITATION_UNAVAILABLE);
        var digest = tokens.digest(token);
        var candidate = invitations.recipient(actor, digest, false).orElseThrow(this::unavailable);
        access.lockInvitationClaim(actor, candidate.organizationId());
        var invitation = invitations.recipient(actor, digest, true).orElseThrow(this::unavailable);
        var member = staff.pending(actor, invitation.organizationId(), invitation.id()).orElseThrow(() -> new AccessFailure(AccessFailure.Kind.CONFLICT));
        try {
            if (!invitations.accept(invitation.organizationId(), invitation.id(), actor)) throw unavailable();
        } catch (DataIntegrityViolationException expiredAtBoundary) { throw unavailable(); }
        audit.securityChange(actor, invitation.organizationId(), "invitation", invitation.id(), "INVITATION_CLAIMED",
                Map.of("outcome", "SUCCEEDED", "after", "ACCEPTED", "membershipId", member, "membershipState", "PENDING"), "Recipient requested administrator identity review");
        return new Claim(invitation.organizationId(), member);
    }

    @Transactional
    public void revoke(UUID actor, UUID org, UUID id, long version, String reason) {
        access.requireAdmin(actor, org, true);
        StaffService.reason(reason);
        var invitation = invitations.lock(org, id).orElseThrow(OrganizationUnavailableException::new);
        if (invitation.version() != version || !invitation.status().equals("PENDING")) StaffService.conflict();
        invitations.revoke(org, id);
        audit.securityChange(actor, org, "invitation", id, "INVITATION_REVOKED", Map.of("outcome", "SUCCEEDED", "before", "PENDING", "after", "REVOKED"), reason);
    }

    private AccessFailure unavailable() { return new AccessFailure(AccessFailure.Kind.INVITATION_UNAVAILABLE); }
}
