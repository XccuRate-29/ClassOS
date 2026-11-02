package com.classos.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class UserDtos {
    private UserDtos() {}

    // BCrypt only uses the first 72 bytes of a password, hence the max length.
    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Role role,
            Long departmentId) {}

    /** One user per line: email,full name,role. A header line starting with "email" is skipped. */
    public record ImportRequest(@NotBlank @Size(max = 200_000) String csv,
                                @NotBlank @Size(min = 10, max = 72) String temporaryPassword) {}

    public record ImportError(int line, String text, String message) {}

    public record ImportResult(int created, java.util.List<ImportError> errors) {}

    public record ActiveRequest(boolean active) {}

    public record PasswordChangeRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 10, max = 72) String newPassword) {}

    public record UserResponse(Long id, String email, String fullName, Role role,
                               Long departmentId, boolean active, OffsetDateTime createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                    u.getDepartmentId(), u.isActive(), u.getCreatedAt());
        }
    }
}
