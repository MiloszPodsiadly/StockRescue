package com.stockrescue.access.application;

import java.util.UUID;

import com.stockrescue.access.persistence.AccessRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialOrganizationOwnership {
    private final AccessRepository access;
    private final AuditWriter audit;

    public InitialOrganizationOwnership(AccessRepository access, AuditWriter audit) {
        this.access = access;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(UUID actor, UUID organization) {
        var membership = access.assignInitialOwner(actor, organization);
        audit.organizationAction(actor, organization, "membership", membership, "INITIAL_OWNER_ASSIGNED");
    }
}
