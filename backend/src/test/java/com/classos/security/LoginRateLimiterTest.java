package com.classos.security;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-20T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAnAccountAfterRepeatedFailuresUntilTheWindowEnds() {
        MutableClock c = new MutableClock();
        LoginRateLimiter rl = new LoginRateLimiter(c, Duration.ofMinutes(15), 5, 30);
        for (int i = 0; i < 4; i++) rl.recordFailure("asha@x", "10.0.0.1");
        assertEquals(0, rl.blockedForSeconds("asha@x", "10.0.0.1"));
        rl.recordFailure("asha@x", "10.0.0.1");
        assertTrue(rl.blockedForSeconds("asha@x", "10.9.9.9") > 0, "blocked from any address");
        assertEquals(0, rl.blockedForSeconds("ravi@x", "10.0.0.1"), "other accounts unaffected");
        c.now = c.now.plus(Duration.ofMinutes(15));
        assertEquals(0, rl.blockedForSeconds("asha@x", "10.0.0.1"));
    }

    @Test
    void blocksAnAddressSprayingManyAccounts() {
        LoginRateLimiter rl = new LoginRateLimiter(new MutableClock(), Duration.ofMinutes(15), 5, 30);
        for (int i = 0; i < 30; i++) rl.recordFailure("user" + i + "@x", "10.6.6.6");
        assertTrue(rl.blockedForSeconds("someone-else@x", "10.6.6.6") > 0);
    }

    @Test
    void successResetsTheAccountAndPurgeFreesMemory() {
        MutableClock c = new MutableClock();
        LoginRateLimiter rl = new LoginRateLimiter(c, Duration.ofMinutes(15), 5, 30);
        for (int i = 0; i < 4; i++) rl.recordFailure("k@x", "10.0.0.2");
        rl.recordSuccess("k@x");
        rl.recordFailure("k@x", "10.0.0.2");
        assertEquals(0, rl.blockedForSeconds("k@x", "10.0.0.2"));
        c.now = c.now.plus(Duration.ofMinutes(16));
        rl.purgeExpired();
        assertEquals(0, rl.size());
    }
}
