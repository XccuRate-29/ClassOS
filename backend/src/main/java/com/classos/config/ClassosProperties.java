package com.classos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "classos")
public record ClassosProperties(Security security, Bootstrap bootstrap, Solver solver, DevSeed devSeed,
                                Attendance attendance, Storage storage, Grading grading,
                                LoginLimits loginLimits) {

    public record Security(String jwtSecret, long jwtTtlMinutes, List<String> corsAllowedOrigins) {}

    public record Bootstrap(String adminEmail, String adminPassword, String adminName) {}

    /** The C++ timetable solver service. */
    public record Solver(String url, int readTimeoutSeconds, int timeLimitMs) {}

    /** Optional sample data for development and demos. */
    public record DevSeed(boolean enabled, String password) {}

    /**
     * Secure attendance. secret = base64 HMAC key (32+ bytes), separate from the JWT key.
     * allowedCidrs empty = no campus-network check.
     */
    public record Attendance(String secret, int windowSeconds, int sessionMinutes, int maxFailedAttempts,
                             List<String> allowedCidrs, int minimumPercent) {}

    /** Uploaded files: kept on disk under dir, never inside the database. */
    public record Storage(String dir, int maxUploadMb) {}

    /** Evaluation queue defaults; a teacher can override them per request. */
    public record Grading(double agingPerHour, double priorityStep, int quantum) {}

    /** Failed sign-in limits: per account and per IP address, within windowMinutes. */
    public record LoginLimits(int windowMinutes, int maxPerAccount, int maxPerAddress) {}
}
