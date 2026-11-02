package com.classos.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Clears finished rate-limit windows every few minutes so memory use stays bounded. */
@Component
public class LoginLimiterSweeper {
    private final LoginRateLimiter limiter;

    public LoginLimiterSweeper(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void purge() {
        limiter.purgeExpired();
    }
}
