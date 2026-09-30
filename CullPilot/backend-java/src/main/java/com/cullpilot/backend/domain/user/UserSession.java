package com.cullpilot.backend.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserSession() {
    }

    public UserSession(
            String id,
            String userId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
