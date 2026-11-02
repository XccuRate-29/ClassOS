package com.classos.auth;

import com.classos.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Placeholder role-scoped endpoints. They prove RBAC works end to end and give each
// React dashboard something to call; later milestones replace them with real data.
@RestController
public class RoleHomeController {

    @GetMapping("/api/teacher/home")
    public Map<String, Object> teacher(@AuthenticationPrincipal AuthenticatedUser u) {
        return Map.of("dashboard", "teacher", "userId", u.id());
    }

    @GetMapping("/api/student/home")
    public Map<String, Object> student(@AuthenticationPrincipal AuthenticatedUser u) {
        return Map.of("dashboard", "student", "userId", u.id());
    }

    @GetMapping("/api/lab/home")
    public Map<String, Object> lab(@AuthenticationPrincipal AuthenticatedUser u) {
        return Map.of("dashboard", "lab", "userId", u.id());
    }
}
