package io.github.lengsukq.opaldesk.daemon.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Single source of truth for lowercase hexadecimal encoding and SHA-256 hashing.
 * Pure Java 11 with zero Android runtime dependencies.
 */
public final class CryptoHex {
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private CryptoHex() {}

    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_DIGITS[v >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] sha256(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return toHex(sha256(data));
    }

    public static String sha256Hex(String utf8Text) {
        if (utf8Text == null) {
            throw new IllegalArgumentException("utf8Text must not be null");
        }
        return sha256Hex(utf8Text.getBytes(StandardCharsets.UTF_8));
    }
}
