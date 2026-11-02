package com.classos.attendance;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests: no Spring, no database. */
class AttendanceCodesTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE = {1, 2, 3, 4, 5, 6, 7, 8};
    private final AttendanceCodes codes = new AttendanceCodes(KEY, 10);

    @Test
    void truncationMatchesRfc4226TestVectors() throws Exception {
        // RFC 4226 Appendix D: key "12345678901234567890", HMAC-SHA1, counters 0 and 1.
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("12345678901234567890".getBytes(StandardCharsets.US_ASCII), "HmacSHA1"));
        assertEquals(755224, AttendanceCodes.truncate(mac.doFinal(ByteBuffer.allocate(8).putLong(0).array())) % 1_000_000);
        assertEquals(287082, AttendanceCodes.truncate(mac.doFinal(ByteBuffer.allocate(8).putLong(1).array())) % 1_000_000);
    }

    @Test
    void currentAndPreviousWindowAreValidOlderIsExpired() {
        Instant now = Instant.ofEpochSecond(1_800_000_005L);
        long w = codes.windowOf(now);
        assertEquals(AttendanceCodes.Check.VALID, codes.check(7, NONCE, codes.codeFor(7, NONCE, w), now));
        assertEquals(AttendanceCodes.Check.VALID, codes.check(7, NONCE, codes.codeFor(7, NONCE, w - 1), now));
        String old = codes.codeFor(7, NONCE, w - 2);
        if (!old.equals(codes.codeFor(7, NONCE, w)) && !old.equals(codes.codeFor(7, NONCE, w - 1))) {
            assertEquals(AttendanceCodes.Check.EXPIRED, codes.check(7, NONCE, old, now));
        }
    }

    @Test
    void malformedCodesAreWrong() {
        Instant now = Instant.now();
        for (String bad : List.of("", "12345", "1234567", "12a456", " 123456")) {
            assertEquals(AttendanceCodes.Check.WRONG, codes.check(7, NONCE, bad, now));
        }
        assertEquals(AttendanceCodes.Check.WRONG, codes.check(7, NONCE, null, now));
    }

    @Test
    void codesAreSixDigitsAndDifferBetweenSessions() {
        int same = 0;
        for (long w = 0; w < 200; w++) {
            String a = codes.codeFor(1, NONCE, w), b = codes.codeFor(2, NONCE, w);
            assertTrue(a.matches("\\d{6}"));
            if (a.equals(b)) same++;
        }
        assertTrue(same <= 1, "different sessions should practically never share a code");
    }

    @Test
    void rejectsWeakKeysAndOddWindows() {
        assertThrows(IllegalArgumentException.class, () -> new AttendanceCodes(new byte[16], 10));
        assertThrows(IllegalArgumentException.class, () -> new AttendanceCodes(KEY, 1));
    }
}
