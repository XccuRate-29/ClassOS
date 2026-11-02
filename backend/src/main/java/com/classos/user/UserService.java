package com.classos.user;

import com.classos.common.ApiException;
import com.classos.user.UserDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserService {

    private static final int MAX_IMPORT_LINES = 1000;

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public User create(CreateUserRequest req) {
        String email = normalizeEmail(req.email());
        // Friendly early check. The unique index is the real guarantee: two concurrent
        // requests can both pass this check, and the database rejects the second
        // (handled as 409 in GlobalExceptionHandler).
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "A user with this email already exists");
        }
        User user = new User(email, encoder.encode(req.password()), req.fullName().trim(),
                req.role(), req.departmentId());
        return users.saveAndFlush(user);  // flush so created_at is generated and errors surface here
    }

    /**
     * Bulk import from CSV (email,full name,role). Every line is validated first and
     * reported individually; only valid, new users are created. All imported users get
     * the same temporary password and should change it at first sign-in.
     * Simple CSV: names must not contain commas.
     */
    @Transactional
    public ImportResult importCsv(ImportRequest req) {
        String[] lines = req.csv().split("\\r?\\n");
        if (lines.length > MAX_IMPORT_LINES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Import at most " + MAX_IMPORT_LINES + " users at a time.");
        }
        List<ImportError> errors = new ArrayList<>();
        List<CreateUserRequest> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("email"))) continue;
            String[] f = line.split(",", -1);
            if (f.length != 3) { errors.add(new ImportError(i + 1, line, "Expected 3 columns: email,full name,role")); continue; }
            String email = normalizeEmail(f[0]);
            String name = f[1].trim();
            String roleText = f[2].trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) { errors.add(new ImportError(i + 1, line, "Not a valid email")); continue; }
            if (name.isEmpty() || name.length() > 120) { errors.add(new ImportError(i + 1, line, "Name missing or too long")); continue; }
            Role role;
            try {
                role = Role.valueOf(roleText);
            } catch (IllegalArgumentException e) {
                errors.add(new ImportError(i + 1, line, "Role must be STUDENT, TEACHER, LAB_ASSISTANT or ADMIN"));
                continue;
            }
            if (!seen.add(email)) { errors.add(new ImportError(i + 1, line, "Email appears twice in this file")); continue; }
            if (users.existsByEmail(email)) { errors.add(new ImportError(i + 1, line, "A user with this email already exists")); continue; }
            valid.add(new CreateUserRequest(email, req.temporaryPassword(), name, role, null));
        }
        // One hash for everyone: BCrypt is deliberately slow, and the password is shared anyway.
        String hash = encoder.encode(req.temporaryPassword());
        for (CreateUserRequest c : valid) {
            users.save(new User(c.email(), hash, c.fullName(), c.role(), null));
        }
        users.flush();
        return new ImportResult(valid.size(), errors);
    }

    /** An admin cannot deactivate themselves or the last active admin, so the system is never locked out. */
    @Transactional
    public User setActive(long targetId, long actingAdminId, boolean active) {
        User u = users.findById(targetId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        if (!active && targetId == actingAdminId) {
            throw new ApiException(HttpStatus.CONFLICT, "You can't deactivate your own account.");
        }
        if (!active && u.getRole() == Role.ADMIN && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "At least one active admin must remain.");
        }
        u.setActive(active);
        return users.saveAndFlush(u);
    }

    /** 400, not 401, for a wrong current password: a 401 would sign the user out in the frontend. */
    @Transactional
    public void changePassword(long userId, PasswordChangeRequest req) {
        User u = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Your current password is not correct.");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a password different from your current one.");
        }
        u.setPasswordHash(encoder.encode(req.newPassword()));
        users.saveAndFlush(u);
    }
}
