CREATE TABLE IF NOT EXISTS inbox_events
(
    message_id        VARCHAR(64)                                   NOT NULL,
    event_type        VARCHAR(64)                                   NOT NULL,
    payload           JSONB                                         NOT NULL,
    headers           JSONB                                         NULL,
    trace_info        VARCHAR(512)                                  NULL,
    status            VARCHAR(64)              DEFAULT 'PROCESSING' NOT NULL,
    retry_count       INT                      DEFAULT 0            NOT NULL,
    reason            VARCHAR(1000)                                 NULL,
    next_execution_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()        NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW()        NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW()        NOT NULL,
    CONSTRAINT pk_inbox_events PRIMARY KEY (message_id)
);

ALTER TABLE IF EXISTS inbox_events
    ADD COLUMN if not exists trace_info VARCHAR(512) NULL;

CREATE INDEX IF NOT EXISTS idx_inbox_recovery_processing
    ON inbox_events (next_execution_at, updated_at)
    WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_inbox_recovery_failed
    ON inbox_events (next_execution_at)
    WHERE status = 'FAILED';

CREATE INDEX IF NOT EXISTS idx_inbox_purge_historical
    ON inbox_events (updated_at)
    WHERE status = 'PROCESSED';