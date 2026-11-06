package com.stockrescue.catalog.application;

import java.util.Objects;
import java.util.UUID;
import com.stockrescue.catalog.application.ManufacturerLot.DamageState;
import com.stockrescue.catalog.application.ManufacturerLot.RecallState;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.EvidenceProvenance;

/** Records metadata observations; existing recall/damage blocks cannot be cleared by an admin toggle. */
public record LotSafetyEvidence(long expectedVersion,String reason,RecallState recallState,DamageState damageState,EvidenceProvenance provenance) {
    public LotSafetyEvidence {
        if (expectedVersion<0 || expectedVersion==Long.MAX_VALUE || reason==null || reason.isBlank() || reason.length()>500
                || reason.codePoints().anyMatch(Character::isISOControl) || recallState==null || damageState==null
                || provenance==null || provenance.reference()==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_SAFETY_EVIDENCE);
        reason=reason.strip();
    }
    public byte[] fingerprint(UUID lot) {
        return CatalogCommandDigest.of("SAFETY_STATUS",lot.toString(),Long.toString(expectedVersion),reason,recallState.name(),damageState.name(),provenance.source(),provenance.reference());
    }
    public void requireTransition(ManufacturerLot before) {
        if (before.recallState()==RecallState.RECALLED && recallState!=RecallState.RECALLED
                || before.damageState()==DamageState.DAMAGED && damageState!=DamageState.DAMAGED)
            throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        if (before.recallState()==recallState && before.damageState()==damageState && Objects.equals(before.statusProvenance(),provenance))
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_SAFETY_EVIDENCE);
    }
}
