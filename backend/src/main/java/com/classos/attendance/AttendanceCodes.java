package com.classos.attendance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Rotating 6-digit attendance codes, built like TOTP (RFC 6238):
 *
 *   window = floor(unix seconds / windowSeconds)
 *   code   = truncate(HMAC-SHA256(serverKey, sessionId || sessionNonce || window)) mod 10^6
 *
 * Nothing is stored per rotation: the server recomputes the code to check it, so
 * rotation costs no database writes however many sessions run at once.
 * The per-session random nonce means two sessions never share codes, and the
 * server key (never in the database) means a database leak alone cannot forge them.
 *
 * Plain Java, no Spring, so it can be unit-tested on its own.
 */
public final class AttendanceCodes {

    public enum Check { VALID, EXPIRED, WRONG }

    /** How far back a code is recognised as "expired" rather than "wrong" (for messages and audit). */
    static final int RECENT_WINDOWS = 12;

    private final byte[] key;
    private final int windowSeconds;

    public AttendanceCodes(byte[] key, int windowSeconds) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("attendance key must be at least 32 bytes");
        if (windowSeconds < 5 || windowSeconds > 120) throw new IllegalArgumentException("window must be 5-120 seconds");
        this.key = key.clone();
        this.windowSeconds = windowSeconds;
    }

    public int windowSeconds() { return windowSeconds; }

    public long windowOf(Instant t) { return Math.floorDiv(t.getEpochSecond(), windowSeconds); }

    /** The instant the given window ends and the next code takes over. */
    public Instant windowEnd(long window) { return Instant.ofEpochSecond((window + 1) * windowSeconds); }

    public String codeFor(long sessionId, byte[] nonce, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");  // Mac is not thread-safe: one per call
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(ByteBuffer.allocate(8).putLong(sessionId).array());
            mac.update(nonce);
            mac.update(ByteBuffer.allocate(8).putLong(window).array());
            return String.format("%06d", truncate(mac.doFinal()) % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * The current and the previous window are accepted, so a code scanned just before
     * it rotates still works: a code is usable for between windowSeconds and 2 x windowSeconds.
     */
    public Check check(long sessionId, byte[] nonce, String submitted, Instant now) {
        if (submitted == null || !submitted.matches("\\d{6}")) return Check.WRONG;
        long w = windowOf(now);
        boolean valid = false;
        for (long k = 0; k <= 1; k++) valid |= sameCode(codeFor(sessionId, nonce, w - k), submitted);
        if (valid) return Check.VALID;
        for (long k = 2; k <= RECENT_WINDOWS; k++) {
            if (sameCode(codeFor(sessionId, nonce, w - k), submitted)) return Check.EXPIRED;
        }
        return Check.WRONG;
    }

    /** RFC 4226 dynamic truncation: 31 bits taken from an offset chosen by the last nibble. */
    static int truncate(byte[] hmac) {
        int offset = hmac[hmac.length - 1] & 0x0f;
        return ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) << 8)
                | (hmac[offset + 3] & 0xff);
    }

    /** Constant-time comparison, so response timing reveals nothing about how many digits matched. */
    private static boolean sameCode(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
