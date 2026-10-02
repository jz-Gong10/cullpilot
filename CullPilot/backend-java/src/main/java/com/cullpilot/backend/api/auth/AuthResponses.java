package com.cullpilot.backend.api.auth;

import com.cullpilot.backend.domain.user.User;
import com.cullpilot.backend.domain.user.UserStatus;

import java.time.Instant;

public final class AuthResponses {

    private AuthResponses() {
    }

    public record UserResponse(
            String id,
            String email,
            String displayName,
            UserStatus status,
            Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getStatus(),
                    user.getCreatedAt());
        }
    }

    public record AuthResponse(
            UserResponse user,
            String tokenType,
            String accessToken,
            Instant expiresAt,
            boolean appearanceOnboardingRequired) {

        @Override
        public String toString() {
            return "AuthResponse[user=" + user + ", tokenType=" + tokenType
                    + ", accessToken=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    public record LogoutResponse(boolean success) {
    }
}
