CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id       UUID         NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    processed_at    TIMESTAMP,

    CONSTRAINT fk_outbox_payout FOREIGN KEY (payout_id) REFERENCES payouts(id)
);

CREATE INDEX idx_outbox_status    ON outbox_events (status);
CREATE INDEX idx_outbox_payout_id ON outbox_events (payout_id);