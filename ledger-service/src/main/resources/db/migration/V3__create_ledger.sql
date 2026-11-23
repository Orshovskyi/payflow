-- Ledger Service (ADR-0008): the decision for every transfer and, for approved ones, a double-entry posting.
CREATE TABLE ledger_transfers
(
    transfer_id      UUID PRIMARY KEY,
    source_wallet_id UUID           NOT NULL,
    target_wallet_id UUID           NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    decision         VARCHAR(20)    NOT NULL,
    failure_reason   VARCHAR(50),
    decided_at       TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_ledger_transfers_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);

-- Double entry: every approved transfer is one DEBIT of the source and one CREDIT of the target, equal amounts.
-- The sum of credits minus debits over all entries is therefore always zero.
CREATE TABLE ledger_entries
(
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID           NOT NULL REFERENCES ledger_transfers (transfer_id),
    wallet_id   UUID           NOT NULL,
    direction   VARCHAR(6)     NOT NULL,
    amount      NUMERIC(19, 4) NOT NULL,
    posted_at   TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transfer ON ledger_entries (transfer_id);
CREATE INDEX idx_ledger_entries_wallet ON ledger_entries (wallet_id);
