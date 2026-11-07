package com.stockrescue.catalog.application;

import java.util.UUID;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.DateMarking;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.LotIdentityState;

public record LotData(UUID productId,String lotCode,LotIdentityState identityState,String manufacturerIdentityKey,
                      EvidenceProvenance manufacturerProvenance,boolean manufacturerVerified,DateMarking dateMarking) {
    public LotData {
        if (productId==null || identityState==null || dateMarking==null) invalid();
        lotCode=identifier(lotCode,128);
        if (manufacturerIdentityKey!=null) manufacturerIdentityKey=identifier(manufacturerIdentityKey,200);
        if (identityState==LotIdentityState.CONFIRMED) {
            if (!manufacturerVerified || manufacturerIdentityKey==null || manufacturerProvenance==null || manufacturerProvenance.reference()==null) invalid();
        } else if (manufacturerVerified) invalid();
    }
    public byte[] fingerprint() {
        var source=dateMarking.provenance().orElse(null);
        return CatalogCommandDigest.of(productId.toString(),lotCode,identityState.name(),manufacturerIdentityKey,
                manufacturerProvenance==null?null:manufacturerProvenance.source(),manufacturerProvenance==null?null:manufacturerProvenance.reference(),Boolean.toString(manufacturerVerified),
                dateMarking.labeledValue(),dateMarking.type().name(),dateMarking.precision().name(),
                source==null?null:source.source(),source==null?null:source.reference());
    }
    private static String identifier(String value,int maximum) {
        if (value==null || value.isBlank() || value.strip().length()>maximum || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value.strip();
    }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT); }
}
