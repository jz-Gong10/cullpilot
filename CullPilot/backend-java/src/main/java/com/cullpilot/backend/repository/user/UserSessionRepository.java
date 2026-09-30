package com.cullpilot.backend.repository.user;

import com.cullpilot.backend.domain.user.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(
            String tokenHash, Instant now);

    Optional<UserSession> findByTokenHash(String tokenHash);

    void deleteAllByUserId(String userId);
}
