package com.stockrescue.catalog.domain;

import java.time.LocalDate;
import java.util.UUID;

/** A date cutoff together with the immutable policy version and rule that derived it. */
public record PolicyDerivedCutoff(LocalDate value, UUID policyVersionId, int policyVersion,
                                 String normalizationVersion) {
    public PolicyDerivedCutoff {
        if (value == null || policyVersionId == null || policyVersion < 1
                || normalizationVersion == null || normalizationVersion.isBlank()
                || normalizationVersion.length() > 64
                || normalizationVersion.codePoints().anyMatch(Character::isISOControl)) {
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_DATE_MARKING);
        }
    }
}
