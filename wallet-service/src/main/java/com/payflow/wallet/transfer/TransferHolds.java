package com.payflow.wallet.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code transfer_holds} table: what the Wallet Service did with each transfer (ADR-0008). Every write happens
 * in the transaction that changes the balances, so the table always matches the money.
 */
@Repository
public class TransferHolds {

    public enum Status { HELD, CAPTURED, RELEASED, REJECTED }

    public record Hold(UUID transferId, UUID sourceWalletId, UUID targetWalletId, BigDecimal amount, Status status) {
    }

    private final JdbcTemplate jdbcTemplate;

    public TransferHolds(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The primary key on transfer id is a second line of defence: even if deduplication by message id failed,
     * a transfer cannot be reserved twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID transferId, UUID sourceWalletId, UUID targetWalletId, BigDecimal amount, Status status,
                       String failureReason) {
        jdbcTemplate.update("""
                        INSERT INTO transfer_holds
                            (transfer_id, source_wallet_id, target_wallet_id, amount, status, failure_reason)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                transferId, sourceWalletId, targetWalletId, amount, status.name(), failureReason);
    }

    /** Reads the hold and locks its row until the end of the transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Hold> lock(UUID transferId) {
        return jdbcTemplate.query("""
                        SELECT transfer_id, source_wallet_id, target_wallet_id, amount, status
                        FROM transfer_holds WHERE transfer_id = ? FOR UPDATE
                        """,
                (rs, i) -> new Hold(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getBigDecimal(4), Status.valueOf(rs.getString(5))),
                transferId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateStatus(UUID transferId, Status status, String failureReason) {
        jdbcTemplate.update("""
                        UPDATE transfer_holds SET status = ?, failure_reason = ?, updated_at = clock_timestamp()
                        WHERE transfer_id = ?
                        """,
                status.name(), failureReason, transferId);
    }

    /** Money that has left the source wallets but has not reached the target yet. */
    public BigDecimal heldTotal() {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM transfer_holds WHERE status = 'HELD'", BigDecimal.class);
    }
}
