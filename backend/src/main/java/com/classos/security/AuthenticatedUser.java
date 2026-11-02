package com.classos.security;

import com.classos.user.Role;

// The principal stored in the SecurityContext for each request, built from JWT claims.
public record AuthenticatedUser(Long id, String email, Role role) {}
