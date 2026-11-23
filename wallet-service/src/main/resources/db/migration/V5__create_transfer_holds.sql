-- Multi-step saga (ADR-0008): funds are first reserved (held), then captured to the target wallet or released
-- back to the source wallet (compensation). One row per transfer, written in the same transaction as the balance
-- change, so it is the Wallet Service's ground truth for reconciliation. It replaces applied_transfers.
--   HELD      debited from the source, waiting for the Ledger Service decision
--   CAPTURED  credited to the target (transfer done)
--   RELEASED  returned to the source (compensated)
--   REJECTED  nothing was debited (insufficient funds, unknown wallet, currency mismatch)
-- Money invariant: sum(wallets.balance) + sum(amount of HELD rows) stays constant.
CREATE TABLE transfer_holds
(
    transfer_id      UUID PRIMARY KEY,
    source_wallet_id UUID           NOT NULL,
    target_wallet_id UUID           NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    failure_reason   VARCHAR(50),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_transfer_holds_status CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED', 'REJECTED'))
);

DROP TABLE applied_transfers;
