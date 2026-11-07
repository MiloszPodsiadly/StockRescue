package com.stockrescue.catalog.application;

import java.util.UUID;
import com.stockrescue.catalog.domain.CatalogFailure;

public record FoodSafetyPolicyApprovalRevocationCommand(UUID requestId,String reason) {
    public FoodSafetyPolicyApprovalRevocationCommand {
        if (requestId==null || reason==null || reason.isBlank() || reason.length()>500
                || reason.codePoints().anyMatch(Character::isISOControl)) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        reason=reason.strip();
    }
    public byte[] fingerprint(UUID organizationId,UUID approvalId) {
        return CatalogCommandDigest.of(organizationId.toString(),approvalId.toString(),requestId.toString(),reason);
    }
}
