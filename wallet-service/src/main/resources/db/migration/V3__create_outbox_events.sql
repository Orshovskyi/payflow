-- Transactional Outbox: events are written here in the same transaction as the state change and published to
-- Kafka by a relay. The table exists for every strategy, so the schema is identical across experiment runs;
-- with the naive strategy it simply stays empty.
CREATE TABLE outbox_events
(
    id           UUID PRIMARY KEY,
    seq          BIGSERIAL    NOT NULL UNIQUE,
    topic        VARCHAR(255) NOT NULL,
    message_key  VARCHAR(255) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ
);

-- The relay only ever looks for unpublished rows in insertion order; a partial index keeps that cheap
-- no matter how many published rows are waiting for cleanup.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
