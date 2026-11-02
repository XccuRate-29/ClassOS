package com.classos.auth;

import com.classos.auth.AuthDtos.LoginRequest;
import com.classos.auth.AuthDtos.LoginResponse;
import com.classos.common.ApiException;
import com.classos.security.JwtService;
import com.classos.security.LoginRateLimiter;
import com.classos.user.User;
import com.classos.user.UserDtos.UserResponse;
import com.classos.user.UserRepository;
import com.classos.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginRateLimiter limiter;
    // Used when the email does not exist, so that "unknown email" and "wrong password"
    // take the same time. Otherwise response timing reveals which emails are registered.
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, LoginRateLimiter limiter) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.limiter = limiter;
        this.dummyHash = encoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req, String clientAddress) {
        String email = UserService.normalizeEmail(req.email());
        long wait = limiter.blockedForSeconds(email, clientAddress);
        if (wait > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-ins. Try again in " + Math.max(1, (wait + 59) / 60) + " minute(s).");
        }
        Optional<User> found = users.findByEmail(email);
        boolean passwordOk = encoder.matches(req.password(), found.map(User::getPasswordHash).orElse(dummyHash));

        // One generic message for every failure, so attackers cannot probe accounts.
        if (found.isEmpty() || !passwordOk || !found.get().isActive()) {
            limiter.recordFailure(email, clientAddress);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        limiter.recordSuccess(email);
        User user = found.get();
        JwtService.IssuedToken token = jwt.issue(user);
        return new LoginResponse(token.token(), "Bearer", token.expiresInSeconds(), UserResponse.from(user));
    }
}
