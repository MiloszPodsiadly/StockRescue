package com.stockrescue.catalog.application;

import java.util.Objects;
import java.util.UUID;
import com.stockrescue.catalog.domain.*;

public record LotMetadataCorrection(long expectedVersion,String reason,boolean evidenceReviewed,
                                    LotIdentityState identityState,String manufacturerIdentityKey,
                                    EvidenceProvenance manufacturerProvenance,boolean manufacturerVerified,DateMarking dateMarking) {
    public LotMetadataCorrection {
        if (expectedVersion<0 || expectedVersion==Long.MAX_VALUE || reason==null || reason.isBlank() || reason.length()>500
                || reason.codePoints().anyMatch(Character::isISOControl) || !evidenceReviewed || identityState==null
                || manufacturerProvenance==null || dateMarking==null || dateMarking.normalizedCutoff().isPresent()) invalid();
        reason=reason.strip();
        if (manufacturerIdentityKey!=null) manufacturerIdentityKey=manufacturerIdentityKey.strip();
    }

    public byte[] fingerprint(UUID lot) {
        var source=dateMarking.provenance().orElse(null);
        return CatalogCommandDigest.of(lot.toString(),Long.toString(expectedVersion),reason,Boolean.toString(evidenceReviewed),
                identityState.name(),manufacturerIdentityKey,manufacturerProvenance.source(),manufacturerProvenance.reference(),
                Boolean.toString(manufacturerVerified),dateMarking.labeledValue(),dateMarking.type().name(),dateMarking.precision().name(),
                source==null?null:source.source(),source==null?null:source.reference());
    }

    public LotData revisedData(ManufacturerLot current) {
        var before=current.data();
        var after=new LotData(before.productId(),before.lotCode(),identityState,manufacturerIdentityKey,
                manufacturerProvenance,manufacturerVerified,dateMarking);
        boolean confirmation=before.identityState()==LotIdentityState.CANDIDATE && identityState==LotIdentityState.CONFIRMED;
        if (before.manufacturerIdentityKey()!=null && !Objects.equals(before.manufacturerIdentityKey(),after.manufacturerIdentityKey())) conflict();
        if (before.identityState()==LotIdentityState.CONFIRMED && identityState!=LotIdentityState.CONFIRMED) conflict();
        if (!confirmation) {
            if (!Objects.equals(before.manufacturerIdentityKey(),after.manufacturerIdentityKey())) conflict();
            if (before.manufacturerProvenance()!=null && !before.manufacturerProvenance().source().equals(manufacturerProvenance.source())) conflict();
        }
        if (precision(dateMarking.precision())>precision(before.dateMarking().precision())) {
            var assessment=DateLabelAssessment.assess(dateMarking);
            if (dateMarking.provenance().isEmpty() || dateMarking.provenance().orElseThrow().reference()==null
                    || assessment.reasons().stream().anyMatch(reason -> reason==DateLabelAssessment.Reason.MISSING_LABEL
                        || reason==DateLabelAssessment.Reason.UNRECOGNIZED_FORMAT || reason==DateLabelAssessment.Reason.NONEXISTENT_DATE)) invalid();
        }
        if (before.equals(after)) invalid();
        return after;
    }

    private static int precision(DatePrecision value) { return switch(value) { case UNKNOWN -> 0; case YEAR -> 1; case MONTH -> 2; case DAY -> 3; }; }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT); }
    private static void conflict() { throw new CatalogFailure(CatalogFailure.Kind.CONFLICT); }
}
