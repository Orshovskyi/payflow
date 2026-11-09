CREATE TABLE wallets
(
    id         UUID PRIMARY KEY,
    owner_id   VARCHAR(64)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    balance    NUMERIC(19, 4) NOT NULL,
    version    BIGINT         NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallets_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_wallets_owner_id ON wallets (owner_id);
