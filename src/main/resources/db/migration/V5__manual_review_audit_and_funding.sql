-- V5: manual review for payouts whose provider outcome is unknown, an append-only audit log,
-- audited (optionally dual-controlled) funding adjustments, and database-enforced immutability of
-- the ledger journal. Additive only: no existing rows are changed or removed.

-- Payouts whose provider outcome cannot be established are parked in MANUAL_REVIEW with their funds
-- still held; they are neither completed nor refunded until the provider or an operator decides.
ALTER TABLE payouts DROP CONSTRAINT chk_payouts_status;
ALTER TABLE payouts ADD CONSTRAINT chk_payouts_status CHECK (status IN
    ('RESERVING', 'PENDING', 'PROCESSING', 'MANUAL_REVIEW', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'));
CREATE INDEX idx_payouts_manual_review ON payouts (updated_at) WHERE status = 'MANUAL_REVIEW';

-- Funding adjustments post CREDIT/DEBIT ledger entries.
ALTER TABLE ledger_entries DROP CONSTRAINT chk_ledger_entry_type;
ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN
    ('OPENING_BALANCE', 'ADJUSTMENT', 'HOLD', 'RELEASE', 'SETTLE', 'CREDIT', 'DEBIT'));

CREATE TABLE ledger_adjustments (
    id                 UUID         PRIMARY KEY,
    requested_by       VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    account_id         VARCHAR(64)  NOT NULL,
    direction          VARCHAR(10)  NOT NULL,
    amount             BIGINT       NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    reason_code        VARCHAR(32)  NOT NULL,
    note               VARCHAR(500) NOT NULL,
    external_reference VARCHAR(128),
    approval_required  BOOLEAN      NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    decided_by         VARCHAR(64),
    decided_at         TIMESTAMPTZ,
    decision_note      VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ledger_adjustments_request UNIQUE (requested_by, idempotency_key),
    CONSTRAINT chk_ledger_adjustments_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_ledger_adjustments_amount CHECK (amount > 0),
    CONSTRAINT chk_ledger_adjustments_status CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED')),
    -- Dual control: when approval is required, the principal that posts an adjustment must differ from
    -- the one that requested it (a requester may still withdraw, i.e. reject, their own request).
    CONSTRAINT chk_ledger_adjustments_four_eyes CHECK (
        NOT approval_required OR status <> 'POSTED' OR decided_by <> requested_by)
);
CREATE INDEX idx_ledger_adjustments_account ON ledger_adjustments (account_id, created_at);

CREATE TABLE audit_events (
    id          BIGSERIAL    PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(32)  NOT NULL,
    target_id   VARCHAR(64)  NOT NULL,
    details     TEXT         NOT NULL
);
CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id, id);

-- The ledger journal and the audit log are append-only: row updates and deletes are refused.
CREATE FUNCTION forbid_row_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_row_modification();

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_row_modification();
