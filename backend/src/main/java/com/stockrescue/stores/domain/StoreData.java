package com.stockrescue.stores.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public record StoreData(String code, String name, String addressLine1, String city, String postalCode,
                        String countryCode, String zoneId) {
    private static final Set<String> COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));
    public StoreData {
        code = required(code, 32);
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) invalid();
        code = code.toUpperCase(Locale.ROOT);
        name = required(name, 160);
        addressLine1 = optional(addressLine1, 200);
        city = optional(city, 100);
        postalCode = optional(postalCode, 20);
        countryCode = optional(countryCode, 2);
        if (countryCode != null) {
            countryCode = countryCode.toUpperCase(Locale.ROOT);
            if (!COUNTRIES.contains(countryCode)) invalid();
        }
        zoneId = required(zoneId, 100);
        if (!(zoneId.equals("UTC") || zoneId.contains("/")) || !ZoneId.getAvailableZoneIds().contains(zoneId)
                || zoneId.startsWith("posix/") || zoneId.startsWith("right/")) invalid();
    }

    public byte[] fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            // Stable field order and length framing distinguish nulls and embedded separators.
            for (var value : new String[]{code, name, addressLine1, city, postalCode, countryCode, zoneId}) {
                var bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static String required(String value, int maximum) {
        var normalized = optional(value, maximum);
        if (normalized == null) invalid();
        return normalized;
    }
    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.strip();
        if (normalized.length() > maximum || normalized.codePoints().anyMatch(Character::isISOControl)) invalid();
        return normalized;
    }
    private static void invalid() { throw new StoreFailure(StoreFailure.Kind.INVALID_INPUT); }
}
