package com.payflow.ledger.domain;

import com.payflow.events.FailureReason;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 3 of the saga: check the transfer against the ledger rule and post it in double entry. The same for
 * choreography and orchestration. Runs inside the caller's transaction together with the deduplication marker and
 * the outgoing decision, so a transfer is decided exactly once.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /** The decision for a transfer; {@code reason} is null when approved. */
    public record Decision(UUID transferId, boolean approved, FailureReason reason) {
    }

    public record Entry(UUID walletId, String direction, BigDecimal amount) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ProcessedMessages processedMessages;
    private final MeterRegistry meterRegistry;
    private final BigDecimal maxTransferAmount;
    private final String strategy;

    public LedgerService(JdbcTemplate jdbcTemplate, ProcessedMessages processedMessages, MeterRegistry meterRegistry,
                         @Value("${payflow.ledger.max-transfer-amount}") BigDecimal maxTransferAmount,
                         @Value("${payflow.consistency.strategy}") String strategy) {
        this.jdbcTemplate = jdbcTemplate;
        this.processedMessages = processedMessages;
        this.meterRegistry = meterRegistry;
        this.maxTransferAmount = maxTransferAmount;
        this.strategy = strategy;
    }

    /**
     * @return the decision to report, or empty if this message was already processed (its reply was sent then)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Decision> record(UUID messageId, UUID transferId, UUID sourceWalletId, UUID targetWalletId,
                                     BigDecimal amount, String currency) {
        if (!processedMessages.markProcessed(messageId)) {
            meterRegistry.counter("payflow.ledger.duplicates.skipped", "strategy", strategy).increment();
            log.info("Skipping duplicate message {}", messageId);
            return Optional.empty();
        }
        Optional<Decision> existing = find(transferId);
        if (existing.isPresent()) {
            // Same transfer, new message id (e.g. a command re-sent after a crash): report the same decision again.
            return existing;
        }
        boolean approved = amount.compareTo(maxTransferAmount) <= 0;
        FailureReason reason = approved ? null : FailureReason.LIMIT_EXCEEDED;
        jdbcTemplate.update("""
                        INSERT INTO ledger_transfers
                            (transfer_id, source_wallet_id, target_wallet_id, amount, currency, decision, failure_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, sourceWalletId, targetWalletId, amount, currency, approved ? "APPROVED" : "REJECTED",
                reason == null ? null : reason.name());
        if (approved) {
            post(transferId, sourceWalletId, "DEBIT", amount);
            post(transferId, targetWalletId, "CREDIT", amount);
        }
        meterRegistry.counter("payflow.ledger.decisions", "strategy", strategy,
                "decision", approved ? "approved" : "rejected").increment();
        return Optional.of(new Decision(transferId, approved, reason));
    }

    public Optional<Decision> find(UUID transferId) {
        return jdbcTemplate.query("SELECT decision, failure_reason FROM ledger_transfers WHERE transfer_id = ?",
                (rs, i) -> new Decision(transferId, "APPROVED".equals(rs.getString(1)),
                        rs.getString(2) == null ? null : FailureReason.valueOf(rs.getString(2))),
                transferId).stream().findFirst();
    }

    public List<Entry> entries(UUID transferId) {
        return jdbcTemplate.query("SELECT wallet_id, direction, amount FROM ledger_entries WHERE transfer_id = ? ORDER BY id",
                (rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3)), transferId);
    }

    private void post(UUID transferId, UUID walletId, String direction, BigDecimal amount) {
        jdbcTemplate.update("INSERT INTO ledger_entries (transfer_id, wallet_id, direction, amount) VALUES (?, ?, ?, ?)",
                transferId, walletId, direction, amount);
    }
}
