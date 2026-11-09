-- A transfer is the saga instance: its status records how far the distributed operation got.
CREATE TABLE transfers
(
    id               UUID PRIMARY KEY,
    source_wallet_id UUID           NOT NULL,
    target_wallet_id UUID           NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    failure_reason   VARCHAR(255),
    version          BIGINT         NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transfers_distinct_wallets CHECK (source_wallet_id <> target_wallet_id),
    CONSTRAINT chk_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfers_status ON transfers (status);
