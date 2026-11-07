package com.stockrescue.catalog.application;

import java.util.UUID;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.EvidenceProvenance;

public record SourceEvidenceData(UUID storeId,String supplierName,String supplierReference,EvidenceProvenance provenance) {
    public SourceEvidenceData {
        if (provenance==null || invalid(supplierName,160) || invalid(supplierReference,256)) throw new CatalogFailure(CatalogFailure.Kind.INVALID_SOURCE);
    }
    public byte[] fingerprint(UUID lotId) {
        return CatalogCommandDigest.of(lotId.toString(),storeId==null?null:storeId.toString(),supplierName,supplierReference,provenance.source(),provenance.reference());
    }
    private static boolean invalid(String value,int maximum) {
        return value!=null && (value.isBlank() || value.length()>maximum || value.codePoints().anyMatch(Character::isISOControl));
    }
}
