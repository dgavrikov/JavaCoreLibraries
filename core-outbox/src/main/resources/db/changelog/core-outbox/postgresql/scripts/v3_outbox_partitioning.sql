ALTER INDEX IF EXISTS idx_outbox_unpublished RENAME TO idx_outbox_unpublished_old;
ALTER INDEX IF EXISTS idx_outbox_purge_historical RENAME TO idx_outbox_purge_historical_old;
ALTER TABLE outbox_events_old
    RENAME CONSTRAINT pk_outbox_events TO pk_outbox_events_old;

CREATE TABLE IF NOT EXISTS outbox_events
(
    id         BIGSERIAL                              NOT NULL,
    event_type VARCHAR(64)                            NOT NULL,
    key_id     VARCHAR(128)                           NOT NULL,
    payload    JSONB                                  NOT NULL,
    headers    JSONB,
    status     VARCHAR(64)                            NOT NULL,
    reason     TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS outbox_events_default PARTITION OF outbox_events DEFAULT;

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (updated_at, id) WHERE (status = 'NEW');
