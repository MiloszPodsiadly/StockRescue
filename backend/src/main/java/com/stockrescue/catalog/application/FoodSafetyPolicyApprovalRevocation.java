package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

/** Immutable revocation event; revoking never erases the signed evidence. */
public record FoodSafetyPolicyApprovalRevocation(UUID id,UUID organizationId,UUID approvalId,UUID actorUserId,
        UUID recordedByMembershipId,UUID requestId,String reason,byte[] payloadDigest,Instant recordedAt) {
    public FoodSafetyPolicyApprovalRevocation { payloadDigest=payloadDigest.clone(); }
    @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
}
