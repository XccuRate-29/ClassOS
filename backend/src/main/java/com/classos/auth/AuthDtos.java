package com.classos.auth;

import com.classos.user.UserDtos.UserResponse;
import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}
}
