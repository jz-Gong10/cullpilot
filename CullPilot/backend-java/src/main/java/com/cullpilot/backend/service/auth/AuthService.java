package com.cullpilot.backend.service.auth;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.auth.AuthRequests.LoginRequest;
import com.cullpilot.backend.api.auth.AuthRequests.RegisterRequest;
import com.cullpilot.backend.api.auth.AuthResponses.AuthResponse;
import com.cullpilot.backend.api.auth.AuthResponses.UserResponse;
import com.cullpilot.backend.config.AuthProperties;
import com.cullpilot.backend.domain.user.User;
import com.cullpilot.backend.domain.user.UserSession;
import com.cullpilot.backend.domain.user.UserStatus;
import com.cullpilot.backend.repository.user.UserRepository;
import com.cullpilot.backend.repository.user.UserSessionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AuthService(
            UserRepository userRepository,
            UserSessionRepository sessionRepository,
            PasswordEncoder passwordEncoder,
            AuthProperties properties) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_ERROR",
                    "密码的 UTF-8 长度不能超过 72 字节");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "EMAIL_ALREADY_REGISTERED",
                    "该邮箱已经注册");
        }

        String displayName = normalizeDisplayName(request.displayName(), email);
        User user = new User(
                UUID.randomUUID().toString(),
                email,
                passwordEncoder.encode(request.password()),
                displayName,
                Instant.now());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "EMAIL_ALREADY_REGISTERED",
                    "该邮箱已经注册");
        }
        return createAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(this::invalidCredentials);
        if (user.getStatus() != UserStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return createAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(String userId) {
        return UserResponse.from(findActiveUser(userId));
    }

    @Transactional(readOnly = true)
    public Optional<String> authenticateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessionRepository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(
                        hashToken(rawToken.trim()), now)
                .flatMap(session -> userRepository.findByIdAndStatus(session.getUserId(), UserStatus.ACTIVE))
                .map(User::getId);
    }

    @Transactional
    public void logout(String authorizationHeader) {
        String rawToken = bearerToken(authorizationHeader);
        if (rawToken == null) {
            return;
        }
        sessionRepository.findByTokenHash(hashToken(rawToken))
                .ifPresent(session -> {
                    session.revoke(Instant.now());
                    sessionRepository.save(session);
                });
    }

    private AuthResponse createAuthResponse(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTokenTtl());
        String rawToken = createToken();
        UserSession session = new UserSession(
                UUID.randomUUID().toString(),
                user.getId(),
                hashToken(rawToken),
                expiresAt,
                now);
        sessionRepository.save(session);
        return new AuthResponse(UserResponse.from(user), "Bearer", rawToken, expiresAt);
    }

    private User findActiveUser(String userId) {
        return userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "登录状态无效或已过期"));
    }

    private ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "邮箱或密码错误");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName, String email) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        int at = email.indexOf('@');
        String fallback = at > 0 ? email.substring(0, at) : email;
        return fallback.substring(0, Math.min(100, fallback.length()));
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (!authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorizationHeader.substring(7).trim();
        return token.isBlank() ? null : token;
    }
}
