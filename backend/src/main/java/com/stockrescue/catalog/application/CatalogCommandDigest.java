package com.stockrescue.catalog.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class CatalogCommandDigest {
    private CatalogCommandDigest() { }
    static byte[] of(String... values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for (var value:values) {
                var bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());
                if (bytes!=null) digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
