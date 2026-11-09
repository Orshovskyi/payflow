package com.payflow.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A money transfer and, at the same time, the state of its saga instance.
 * Status only moves forward: PENDING -> COMPLETED | FAILED. Repeated outcomes are ignored,
 * which makes result handling idempotent without a separate deduplication table.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "source_wallet_id", nullable = false, updatable = false)
    private UUID sourceWalletId;

    @Column(name = "target_wallet_id", nullable = false, updatable = false)
    private UUID targetWalletId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
        // for JPA
    }

    public Transfer(UUID sourceWalletId, UUID targetWalletId, BigDecimal amount, String currency) {
        if (Objects.equals(sourceWalletId, targetWalletId)) {
            throw new IllegalArgumentException("Source and target wallets must differ");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        this.id = UUID.randomUUID();
        this.sourceWalletId = Objects.requireNonNull(sourceWalletId, "sourceWalletId");
        this.targetWalletId = Objects.requireNonNull(targetWalletId, "targetWalletId");
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = TransferStatus.PENDING;
    }

    /** @return true if the status changed, false if the transfer was already finished */
    public boolean complete() {
        if (status.isTerminal()) {
            return false;
        }
        status = TransferStatus.COMPLETED;
        return true;
    }

    /** @return true if the status changed, false if the transfer was already finished */
    public boolean fail(String reason) {
        if (status.isTerminal()) {
            return false;
        }
        status = TransferStatus.FAILED;
        failureReason = reason;
        return true;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    public UUID getTargetWalletId() {
        return targetWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
