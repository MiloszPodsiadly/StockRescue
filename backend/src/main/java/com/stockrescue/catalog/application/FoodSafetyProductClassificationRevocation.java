package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

/** Immutable revocation event for one product classification attestation. */
public record FoodSafetyProductClassificationRevocation(UUID id,UUID organizationId,UUID attestationId,
        UUID actorUserId,UUID recordedByMembershipId,UUID requestId,String reason,byte[] payloadDigest,Instant recordedAt) {
    public FoodSafetyProductClassificationRevocation { payloadDigest=payloadDigest.clone(); }
    @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
}
