package com.stockrescue.access.application;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.EffectivePermissions.StorePermission;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.persistence.AccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccessPolicy {
    private final AccessRepository access;

    public AccessPolicy(AccessRepository access) { this.access = access; }

    public List<OrganizationAccess> organizations(UUID actor, int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 101) throw new IllegalArgumentException();
        return access.organizations(actor, offset, limit);
    }

    public OrganizationAccess organization(UUID actor, UUID organization) {
        return access.organization(actor, organization).orElseThrow(OrganizationUnavailableException::new);
    }

    public EffectivePermissions permissions(UUID actor, UUID organization) {
        var rows = access.permissions(actor, organization);
        if (rows.isEmpty()) throw new OrganizationUnavailableException();
        var membership = rows.getFirst().organization();
        boolean admin = membership.role() == OrganizationRole.ORG_ADMIN;
        var grants = rows.stream().filter(row -> row.storeId() != null)
                .map(row -> new StorePermission(row.storeId(), row.role(), storeActions(row.role()))).toList();
        return new EffectivePermissions(membership,
                admin ? Arrays.asList(OrganizationAction.values()) : List.of(OrganizationAction.READ_ORGANIZATION),
                admin ? Arrays.asList(StoreAction.values()) : List.of(), grants);
    }

    public OrganizationAccess requireAdmin(UUID actor, UUID organization, boolean write) {
        var membership = write ? lock(actor, organization, true) : organization(actor, organization);
        if (membership.role() != OrganizationRole.ORG_ADMIN) throw new AccessDeniedException("Action unavailable");
        return membership;
    }

    public List<StaffAccess.MemberAction> memberActions(UUID actor, StaffAccess.Member member) {
        var actions = new java.util.ArrayList<StaffAccess.MemberAction>();
        if (member.state() == StaffAccess.MembershipState.REVOKED) return List.of();
        actions.add(StaffAccess.MemberAction.REVOKE);
        if (member.state() == StaffAccess.MembershipState.PENDING) {
            if (!member.userId().equals(actor)) actions.add(StaffAccess.MemberAction.APPROVE);
        } else {
            actions.add(StaffAccess.MemberAction.CHANGE_ROLE);
            if (member.role() == OrganizationRole.MEMBER && !member.userId().equals(actor)) {
                actions.add(StaffAccess.MemberAction.SET_GRANT);
                actions.add(StaffAccess.MemberAction.REVOKE_GRANT);
            }
        }
        return List.copyOf(actions);
    }

    public void requireMemberAction(UUID actor, StaffAccess.Member member, StaffAccess.MemberAction action) {
        if (memberActions(actor, member).contains(action)) return;
        if (member.userId().equals(actor)) throw new AccessDeniedException("Self assignment unavailable");
        throw new AccessFailure(AccessFailure.Kind.CONFLICT);
    }

    public void requireStore(UUID actor, UUID organization, UUID store, StoreAction action, boolean write) {
        requireStore(actor, organization, store, action, write, false);
    }

    public void requireStoreIncludingArchived(UUID actor, UUID organization, UUID store, StoreAction action, boolean write) {
        requireStore(actor, organization, store, action, write, true);
    }

    private void requireStore(UUID actor, UUID organization, UUID store, StoreAction action, boolean write, boolean includeArchived) {
        var membership = write ? lock(actor, organization, false) : organization(actor, organization);
        if (!access.store(organization, store, write, includeArchived && membership.role() == OrganizationRole.ORG_ADMIN)) throw new OrganizationUnavailableException();
        if (membership.role() == OrganizationRole.ORG_ADMIN) return;
        var role = access.storeRole(organization, membership.membershipId(), store, write)
                .orElseThrow(OrganizationUnavailableException::new);
        if (!storeActions(role).contains(action)) throw new AccessDeniedException("Action unavailable");
    }

    public List<StoreAction> storeActions(EffectivePermissions permissions, UUID store, boolean active) {
        if (permissions.allStoreActions().contains(StoreAction.READ_STORE)) {
            return active ? permissions.allStoreActions() : List.of(StoreAction.READ_STORE);
        }
        if (!active) throw new OrganizationUnavailableException();
        return permissions.storeGrants().stream().filter(grant -> grant.storeId().equals(store))
                .findFirst().orElseThrow(OrganizationUnavailableException::new).actions();
    }

    public void lockInvitationClaim(UUID actor, UUID organization) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Invitation claims require the command transaction");
        }
        access.lockOrganization(organization, true);
        if (!access.activeAccount(actor)) throw new org.springframework.security.authentication.BadCredentialsException("Account unavailable");
    }

    private OrganizationAccess lock(UUID actor, UUID organization, boolean securityChange) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Protected writes require the command transaction");
        }
        organization(actor, organization); // Avoid taking locks for foreign identifiers.
        // All security changes take the organization lock first. Store commands share
        // it, then lock their current membership/grant; no lock upgrade is permitted.
        access.lockOrganization(organization, securityChange);
        // A NEW statement after waiting is essential: the pre-lock snapshot can be stale.
        return access.lockMembership(actor, organization).orElseThrow(OrganizationUnavailableException::new);
    }

    private List<StoreAction> storeActions(StoreRole role) {
        return role == StoreRole.STORE_MANAGER
                ? List.of(StoreAction.READ_STORE, StoreAction.EDIT_STORE, StoreAction.MANAGE_STORAGE_ZONES)
                : List.of(StoreAction.READ_STORE);
    }
}
