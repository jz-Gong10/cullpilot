package com.cullpilot.backend.domain.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "decision_history")
public class DecisionHistory {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "asset_id", length = 36, nullable = false, updatable = false)
    private String assetId;

    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_decision", length = 20)
    private AssetDecision previousDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_decision", length = 20, nullable = false, updatable = false)
    private AssetDecision newDecision;

    @Column(nullable = false, length = 20, updatable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DecisionHistory() {
    }

    public DecisionHistory(
            String id,
            String assetId,
            String userId,
            AssetDecision previousDecision,
            AssetDecision newDecision,
            String source,
            Instant createdAt) {
        this.id = id;
        this.assetId = assetId;
        this.userId = userId;
        this.previousDecision = previousDecision;
        this.newDecision = newDecision;
        this.source = source;
        this.createdAt = createdAt;
    }

    public String getAssetId() { return assetId; }
}
