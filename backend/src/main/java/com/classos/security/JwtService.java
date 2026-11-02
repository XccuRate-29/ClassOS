package com.classos.security;

import com.classos.config.ClassosProperties;
import com.classos.user.Role;
import com.classos.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final String ISSUER = "classos";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(ClassosProperties props) {
        String secret = props.security().jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set (see .env.example)");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET must be base64-encoded", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = Duration.ofMinutes(props.security().jwtTtlMinutes());
    }

    public record IssuedToken(String token, long expiresInSeconds) {}

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
        return new IssuedToken(token, ttl.toSeconds());
    }

    /** Verifies signature, issuer and expiry. Throws JwtException or IllegalArgumentException if invalid. */
    public AuthenticatedUser parse(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String role = c.get("role", String.class);
        if (role == null) throw new JwtException("missing role claim");
        return new AuthenticatedUser(Long.valueOf(c.getSubject()), c.get("email", String.class), Role.valueOf(role));
    }
}
