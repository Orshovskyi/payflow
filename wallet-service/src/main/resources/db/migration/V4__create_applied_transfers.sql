-- Wallet-side journal of transfer decisions, written in the same transaction as the balance changes.
-- It records what the Wallet Service actually did, independently of whether the result event ever reached
-- the Transaction Service. The reconciliation compares it with transaction_db.transfers.
-- The primary key guarantees that a transfer is applied at most once, even if deduplication by event id failed.
CREATE TABLE applied_transfers
(
    transfer_id      UUID PRIMARY KEY,
    source_wallet_id UUID           NOT NULL,
    target_wallet_id UUID           NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    outcome          VARCHAR(20)    NOT NULL,
    failure_reason   VARCHAR(50),
    applied_at       TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_applied_transfers_outcome CHECK (outcome IN ('COMPLETED', 'FAILED'))
);
