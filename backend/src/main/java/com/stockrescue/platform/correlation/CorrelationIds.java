package com.stockrescue.platform.correlation;

import java.util.regex.Pattern;

public final class CorrelationIds {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private CorrelationIds() { }

    public static boolean isValid(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    public static String requireValidOrNull(String value) {
        if (value != null && !isValid(value)) {
            throw new IllegalArgumentException("Correlation ID must use the safe 1 to 64 character format");
        }
        return value;
    }
}
