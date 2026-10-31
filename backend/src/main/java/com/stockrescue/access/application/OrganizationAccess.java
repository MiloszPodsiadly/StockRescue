package com.stockrescue.access.application;

import java.time.Instant;
import java.util.UUID;

public record OrganizationAccess(UUID id, String name, Instant createdAt, UUID membershipId, OrganizationRole role) {
    public enum OrganizationRole { ORG_ADMIN, MEMBER }
}
