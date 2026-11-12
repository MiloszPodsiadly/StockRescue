package com.stockrescue.inventory.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InventoryFingerprint {
    private InventoryFingerprint() {}
    static byte[] of(Object... values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update("inventory-command-v1".getBytes(StandardCharsets.US_ASCII));
            for (var value:values) {
                byte[] bytes=value==null?null:value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes==null?-1:bytes.length).array());
                if (bytes!=null) digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
