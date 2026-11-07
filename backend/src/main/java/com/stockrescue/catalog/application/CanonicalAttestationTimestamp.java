package com.stockrescue.catalog.application;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Canonical UTC timestamp representation shared by signed payloads and command fingerprints. */
public final class CanonicalAttestationTimestamp {
    private static final DateTimeFormatter FORMATTER=DateTimeFormatter.ISO_INSTANT;

    private CanonicalAttestationTimestamp() { }

    public static String format(Instant value) {
        return FORMATTER.format(value);
    }

    /** PostgreSQL TIMESTAMPTZ retains microseconds and this contract limits years to its supported application range. */
    public static boolean isDatabaseRepresentable(Instant value) {
        if (value==null || value.getNano()%1_000!=0) return false;
        try {
            int year=value.atOffset(ZoneOffset.UTC).getYear();
            return year>=1 && year<=9999;
        } catch (DateTimeException outsideDatabaseRange) {
            return false;
        }
    }
}
