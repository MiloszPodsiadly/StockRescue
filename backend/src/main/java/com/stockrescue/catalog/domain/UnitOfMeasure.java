package com.stockrescue.catalog.domain;

/** Units supported by the initial packaged-product catalog pilot. */
public enum UnitOfMeasure {
    EA;

    public static UnitOfMeasure parse(String value) {
        if ("EA".equals(value)) return EA;
        throw new CatalogFailure(CatalogFailure.Kind.UNSUPPORTED_UNIT);
    }
}
