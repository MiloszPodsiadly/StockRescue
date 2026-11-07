package com.stockrescue.catalog.domain;

/** Where a date marking was observed, without implying that its source was verified. */
public record EvidenceProvenance(String source, String reference) {
    public EvidenceProvenance {
        if (invalid(source, 256) || reference != null && invalid(reference, 256)) {
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_PROVENANCE);
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl);
    }
}
