CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    event_type VARCHAR(300) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT'))
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (status, available_at, created_at);

CREATE INDEX idx_outbox_processing
    ON outbox_events (status, locked_at);
