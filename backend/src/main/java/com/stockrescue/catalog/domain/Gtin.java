package com.stockrescue.catalog.domain;

/** A validated GTIN kept as text so leading zeroes remain significant. */
public record Gtin(String value) {
    private static final String LENGTHS = "(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})";

    public Gtin {
        if (value == null || !value.matches(LENGTHS) || !hasValidCheckDigit(value)) invalid();
    }

    private static boolean hasValidCheckDigit(String value) {
        int sum = 0;
        boolean multiplyByThree = true;
        for (int index = value.length() - 2; index >= 0; index--) {
            int digit = value.charAt(index) - '0';
            sum += digit * (multiplyByThree ? 3 : 1);
            multiplyByThree = !multiplyByThree;
        }
        return (10 - sum % 10) % 10 == value.charAt(value.length() - 1) - '0';
    }

    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_GTIN); }
}
