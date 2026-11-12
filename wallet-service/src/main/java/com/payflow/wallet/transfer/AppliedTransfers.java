package com.payflow.wallet.transfer;

import com.payflow.events.TransferFailed;
import com.payflow.events.TransferRequested;
import com.payflow.events.TransferResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Journal of what the Wallet Service decided for each transfer. Written in the balance-changing transaction,
 * so it is the ground truth for reconciliation: "did the money actually move?".
 */
@Repository
public class AppliedTransfers {

    private final JdbcTemplate jdbcTemplate;

    public AppliedTransfers(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TransferRequested request, TransferResult result) {
        String outcome = result instanceof TransferFailed ? "FAILED" : "COMPLETED";
        String reason = result instanceof TransferFailed failed ? failed.reason().name() : null;
        jdbcTemplate.update("""
                        INSERT INTO applied_transfers
                            (transfer_id, source_wallet_id, target_wallet_id, amount, outcome, failure_reason)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                request.transferId(), request.sourceWalletId(), request.targetWalletId(), request.amount(),
                outcome, reason);
    }
}
