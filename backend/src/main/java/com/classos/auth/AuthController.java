package com.classos.auth;

import com.classos.auth.AuthDtos.LoginRequest;
import com.classos.auth.AuthDtos.LoginResponse;
import com.classos.common.ApiException;
import com.classos.security.AuthenticatedUser;
import com.classos.user.UserDtos.UserResponse;
import com.classos.user.UserRepository;
import com.classos.user.UserService;
import com.classos.user.UserDtos.PasswordChangeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;
    private final UserService userService;

    public AuthController(AuthService auth, UserRepository users, UserService userService) {
        this.auth = auth;
        this.users = users;
        this.userService = userService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        return auth.login(req, http.getRemoteAddr());
    }

    // Reads the user from the database (not just the token) so the frontend
    // sees current name/role/active status.
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return users.findById(principal.id())
                .filter(u -> u.isActive())
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not available"));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody PasswordChangeRequest req) {
        userService.changePassword(principal.id(), req);
    }
}
