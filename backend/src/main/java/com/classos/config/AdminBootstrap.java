package com.classos.config;

import com.classos.user.Role;
import com.classos.user.UserDtos.CreateUserRequest;
import com.classos.user.UserRepository;
import com.classos.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Creates the first ADMIN from environment variables if no admin exists yet.
// This avoids hard-coding a default password in a migration script.
@Component
@Order(1)  // before DevSeedRunner
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final ClassosProperties props;
    private final UserRepository users;
    private final UserService userService;

    public AdminBootstrap(ClassosProperties props, UserRepository users, UserService userService) {
        this.props = props;
        this.users = users;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.ADMIN)) return;

        ClassosProperties.Bootstrap b = props.bootstrap();
        if (b == null || isBlank(b.adminEmail()) || isBlank(b.adminPassword())) {
            log.warn("No ADMIN user exists. Set ADMIN_EMAIL and ADMIN_PASSWORD to create one at startup.");
            return;
        }
        if (b.adminPassword().length() < 10) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 10 characters");
        }
        userService.create(new CreateUserRequest(b.adminEmail(), b.adminPassword(),
                isBlank(b.adminName()) ? "ClassOS Administrator" : b.adminName(), Role.ADMIN, null));
        log.info("Created initial ADMIN account {}", b.adminEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
