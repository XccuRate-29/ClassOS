package com.classos.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The browser creates a random device id once and sends it in the X-Device-Id header.
 * Only its SHA-256 hash is stored. Honest limit: a determined student can copy the id
 * to another phone; this raises the effort of proxy marking, it does not make it impossible.
 */
public final class DeviceIds {
    private DeviceIds() {}

    /** Hex SHA-256 of a well-formed id, or null when the id is missing or malformed. */
    public static String hash(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9-]{16,128}")) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
