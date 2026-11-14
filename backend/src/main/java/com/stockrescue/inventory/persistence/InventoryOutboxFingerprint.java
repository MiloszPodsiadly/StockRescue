package com.stockrescue.inventory.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class InventoryOutboxFingerprint {
    private InventoryOutboxFingerprint() { }

    public static byte[] digest(Object... values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update("stockrescue-inventory-outbox-v1".getBytes(StandardCharsets.US_ASCII));
            for (var value:values) {
                byte[] bytes=value==null?null:value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes==null?-1:bytes.length).array());
                if(bytes!=null) digest.update(bytes);
            }
            return digest.digest();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String hex(Object... values) { return HexFormat.of().formatHex(digest(values)); }
}
