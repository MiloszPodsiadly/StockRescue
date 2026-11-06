package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.domain.EvidenceProvenance;

public record ManufacturerLot(UUID id,LotData data,UUID confirmedByMembershipId,Instant confirmedAt,
                              RecallState recallState,DamageState damageState,EvidenceProvenance statusProvenance,
                              boolean active,long version,Instant createdAt,Instant updatedAt,Instant archivedAt) {
    public enum RecallState { UNKNOWN, NOT_REPORTED, RECALLED }
    public enum DamageState { UNKNOWN, NOT_REPORTED, DAMAGED }
}
