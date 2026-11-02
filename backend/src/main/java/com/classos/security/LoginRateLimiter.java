package com.classos.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits FAILED sign-ins, so passwords cannot be guessed at speed. Successful sign-ins are
 * never limited (a class of 120 signing in at once is normal).
 *
 *   per account : after maxPerAccount failures in the window, that email is blocked until the
 *                 window ends (stops guessing one person's password)
 *   per address : after maxPerAddress failures in the window, that IP is blocked
 *                 (stops trying one common password against many accounts)
 *
 * Fixed windows kept in a ConcurrentHashMap; compute() makes each update atomic, so
 * concurrent requests cannot both slip under the limit.
 *
 * Trade-offs: state is per backend instance and lost on restart; and anyone can lock an
 * account for one window by failing on purpose (a short, temporary denial of service).
 * Plain Java, no Spring, so it can be unit-tested on its own.
 */
public final class LoginRateLimiter {

    private record Window(Instant start, int failures) {}

    private final Clock clock;
    private final Duration window;
    private final int maxPerAccount;
    private final int maxPerAddress;
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock, Duration window, int maxPerAccount, int maxPerAddress) {
        this.clock = clock;
        this.window = window;
        this.maxPerAccount = maxPerAccount;
        this.maxPerAddress = maxPerAddress;
    }

    /** Seconds until the caller may try again, or 0 if not blocked. */
    public long blockedForSeconds(String email, String address) {
        return Math.max(remaining("a:" + email, maxPerAccount), remaining("i:" + address, maxPerAddress));
    }

    public void recordFailure(String email, String address) {
        bump("a:" + email);
        bump("i:" + address);
    }

    /** A correct password clears the account's failures (not the address's). */
    public void recordSuccess(String email) {
        counters.remove("a:" + email);
    }

    private void bump(String key) {
        Instant now = clock.instant();
        counters.compute(key, (k, w) -> (w == null || expired(w, now)) ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
    }

    private long remaining(String key, int max) {
        Window w = counters.get(key);
        Instant now = clock.instant();
        if (w == null || expired(w, now) || w.failures() < max) return 0;
        return Math.max(1, Duration.between(now, w.start().plus(window)).toSeconds());
    }

    private boolean expired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }

    /** Drops finished windows; called periodically so the map cannot grow without bound. */
    public int purgeExpired() {
        Instant now = clock.instant();
        int before = counters.size();
        counters.entrySet().removeIf(e -> expired(e.getValue(), now));
        return before - counters.size();
    }

    int size() {
        return counters.size();
    }
}
