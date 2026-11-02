package com.classos;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

// One PostgreSQL container shared by all test classes (started once, stopped when
// the JVM exits). Using @Container per class would stop it between classes while
// Spring's cached context still points at it.
@SpringBootTest
public abstract class IntegrationTestBase {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "admin-password-123";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Uploaded files go to a throwaway folder, never into the project. */
    static final java.nio.file.Path STORAGE;

    static {
        POSTGRES.start();
        try {
            STORAGE = java.nio.file.Files.createTempDirectory("classos-files");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        // Test-only secret; never reuse outside tests.
        r.add("classos.security.jwt-secret", () -> "Y2xhc3Nvcy10ZXN0LW9ubHktc2VjcmV0LWtleS0zMmIhIQ==");
        r.add("classos.attendance.secret", () -> "Y2xhc3Nvcy1hdHRlbmRhbmNlLXRlc3Qta2V5LTMyYnkh");
        r.add("classos.storage.dir", STORAGE::toString);
        r.add("classos.bootstrap.admin-email", () -> ADMIN_EMAIL);
        r.add("classos.bootstrap.admin-password", () -> ADMIN_PASSWORD);
    }
}
