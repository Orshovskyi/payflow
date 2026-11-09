-- Idempotent consumer: the id of every handled event is stored in the same transaction as its effects.
-- A redelivered event finds its id here and is skipped, so balances never change twice.
CREATE TABLE processed_messages
(
    message_id   UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
