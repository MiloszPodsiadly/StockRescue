package com.stockrescue.catalog.domain;

import java.util.Optional;

/** Original date-label evidence plus an optional, explicitly policy-derived cutoff. */
public record DateMarking(String labeledValue, DateMarkingType type, DatePrecision precision,
                          Optional<EvidenceProvenance> provenance,
                          Optional<PolicyDerivedCutoff> normalizedCutoff) {
    public DateMarking {
        if (type == null || precision == null || provenance == null || normalizedCutoff == null
                || labeledValue != null && (labeledValue.length() > 256
                    || labeledValue.codePoints().anyMatch(Character::isISOControl))) {
            invalid();
        }
        if (normalizedCutoff.isPresent()
                && (type == DateMarkingType.UNKNOWN || precision == DatePrecision.UNKNOWN
                    || type == DateMarkingType.USE_BY && precision != DatePrecision.DAY
                    || labeledValue == null || labeledValue.isBlank() || provenance.isEmpty())) {
            invalid();
        }
    }

    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_DATE_MARKING); }
}
