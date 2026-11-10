package com.stockrescue.catalog.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical product key: trim ASCII spaces, then uppercase an ASCII SKU.
 * Restricting keys to ASCII keeps application and PostgreSQL uniqueness identical.
 */
public record Sku(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");

    public Sku {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        value = trimAsciiSpaces(value);
        if (!FORMAT.matcher(value).matches()) invalid();
        value = value.toUpperCase(Locale.ROOT);
    }

    private static String trimAsciiSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        return value.substring(start, end);
    }

    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_SKU); }
}
