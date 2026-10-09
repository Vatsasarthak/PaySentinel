-- Timestamps become timezone-aware. Rows written before this migration came from a JVM pinned to
-- Asia/Kolkata (via Maven JVM arguments), so their local values are interpreted in that zone.
ALTER TABLE payouts
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Kolkata';

ALTER TABLE outbox_events
    ALTER COLUMN created_at   TYPE TIMESTAMPTZ USING created_at   AT TIME ZONE 'Asia/Kolkata',
    ALTER COLUMN processed_at TYPE TIMESTAMPTZ USING processed_at AT TIME ZONE 'Asia/Kolkata';

-- funds_status tracks which ledger action (if any) is still owed for a payout.
-- lease_until fences concurrent workers; failure_reason records why a payout failed or was cancelled.
ALTER TABLE payouts
    ADD COLUMN funds_status   VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN lease_until    TIMESTAMPTZ,
    ADD COLUMN failure_reason VARCHAR(64);

-- Backfill existing rows. PENDING payouts hold funds in Redis without a per-payout hold marker,
-- so they are adopted by the reconciler (LEGACY_HELD -> HELD) before they can be processed.
-- The previous implementation released funds synchronously for FAILED payouts.
UPDATE payouts
SET funds_status = CASE status
                       WHEN 'PENDING' THEN 'LEGACY_HELD'
                       WHEN 'COMPLETED' THEN 'SETTLED'
                       ELSE 'RELEASED'
    END;

ALTER TABLE payouts
    ADD CONSTRAINT chk_payouts_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT chk_payouts_status CHECK (status IN
        ('RESERVING', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED')),
    ADD CONSTRAINT chk_payouts_funds_status CHECK (funds_status IN
        ('NONE', 'LEGACY_HELD', 'HELD', 'SETTLE_PENDING', 'SETTLED', 'RELEASE_PENDING', 'RELEASED'));

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'RELAYED'));

CREATE INDEX idx_payouts_status_updated_at ON payouts (status, updated_at);
CREATE INDEX idx_payouts_status_created_at ON payouts (status, created_at);
CREATE INDEX idx_payouts_funds_outstanding ON payouts (funds_status)
    WHERE funds_status IN ('LEGACY_HELD', 'SETTLE_PENDING', 'RELEASE_PENDING');
CREATE INDEX idx_outbox_pending_created_at ON outbox_events (created_at)
    WHERE status = 'PENDING';
