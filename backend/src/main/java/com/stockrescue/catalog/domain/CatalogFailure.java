package com.stockrescue.catalog.domain;

/** A safe, stable failure category for invalid catalog values. */
public final class CatalogFailure extends RuntimeException {
    public enum Kind { INVALID_SKU, INVALID_GTIN, UNSUPPORTED_UNIT, INVALID_PROVENANCE, INVALID_DATE_MARKING, INVALID_PRODUCT, INVALID_LOT, INVALID_SOURCE, INVALID_POLICY, INVALID_SAFETY_EVIDENCE, INVALID_STORAGE_CONTEXT, CONFLICT, INACTIVE }

    private final Kind kind;

    public CatalogFailure(Kind kind) {
        super("Catalog value is invalid.");
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
