package com.stockrescue.catalog.domain;

/** Descriptive catalog grouping. It is not a legal classification or safety approval. */
public enum ProductCategory {
    UNCLASSIFIED("Unclassified"),
    DAIRY("Dairy"),
    BAKERY("Bakery");

    private final String displayName;

    ProductCategory(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }
}
