package com.stockrescue.transfers.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Scope-bound keyset cursors. Cursors never replace current authorization checks. */
public final class TransferCursor {
    private TransferCursor() { }

    public record TimestampId(Instant recordedAt, UUID id) { }
    public record Transit(Instant dispatchedAt, UUID transferId, UUID lineId) { }

    public static String timestampId(String scope, Instant recordedAt, UUID id) {
        return encode("v1|"+scope+"|"+recordedAt+"|"+id);
    }

    public static TimestampId timestampId(String scope, String cursor) {
        var parts=decode(scope,cursor,4);
        try {
            return new TimestampId(Instant.parse(parts[2]),UUID.fromString(parts[3]));
        } catch (RuntimeException invalid) {
            throw new TransferCursorInvalid();
        }
    }

    public static String transit(String scope, Instant dispatchedAt, UUID transferId, UUID lineId) {
        return encode("v1|"+scope+"|"+dispatchedAt+"|"+transferId+"|"+lineId);
    }

    public static Transit transit(String scope, String cursor) {
        var parts=decode(scope,cursor,5);
        try {
            return new Transit(Instant.parse(parts[2]),UUID.fromString(parts[3]),UUID.fromString(parts[4]));
        } catch (RuntimeException invalid) {
            throw new TransferCursorInvalid();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String[] decode(String scope,String cursor,int expectedParts) {
        if(cursor==null || cursor.isBlank() || cursor.length()>512) throw new TransferCursorInvalid();
        try {
            var decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII);
            if(decoded.length()>384) throw new TransferCursorInvalid();
            var parts=decoded.split("\\|",-1);
            if(parts.length!=expectedParts || !parts[0].equals("v1") || !parts[1].equals(scope))
                throw new TransferCursorInvalid();
            return parts;
        } catch (IllegalArgumentException invalid) {
            throw new TransferCursorInvalid();
        }
    }
}
