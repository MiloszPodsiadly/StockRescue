package com.stockrescue.organizations.application;

import java.util.List;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.InitialOrganizationOwnership;
import com.stockrescue.access.application.OrganizationAccess;
import com.stockrescue.organizations.persistence.OrganizationRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    private final OrganizationRepository organizations;
    private final InitialOrganizationOwnership ownership;
    private final AccessPolicy access;
    private final AuditWriter audit;

    public OrganizationService(OrganizationRepository organizations, InitialOrganizationOwnership ownership,
            AccessPolicy access, AuditWriter audit) {
        this.organizations = organizations;
        this.ownership = ownership;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Creation onboard(UUID actor, UUID request, String name) {
        if (request == null || name == null || name.isBlank() || name.strip().length() > 160) throw new InvalidOnboardingException();
        var normalized = name.strip();
        if (normalized.codePoints().anyMatch(Character::isISOControl)) throw new InvalidOnboardingException();
        organizations.lockCreator(actor);
        var previous = organizations.onboarding(actor);
        if (previous.isPresent()) {
            var recorded = previous.orElseThrow();
            if (!recorded.requestId().equals(request) || !recorded.name().equals(normalized)) throw new OnboardingConflictException();
            // A replay must not reactivate a revoked membership or archived organization.
            return new Creation(access.organization(actor, recorded.organizationId()), false);
        }
        var organization = organizations.create(actor, normalized);
        audit.organizationAction(actor, organization, "organization", organization, "ORGANIZATION_CREATED");
        ownership.assign(actor, organization);
        organizations.remember(actor, request, normalized, organization);
        return new Creation(access.organization(actor, organization), true);
    }

    public List<OrganizationAccess> list(UUID actor, int offset, int limit) { return access.organizations(actor, offset, limit); }
    public OrganizationAccess detail(UUID actor, UUID organization) { return access.organization(actor, organization); }

    public record Creation(OrganizationAccess organization, boolean created) { }
}
