-- PostgreSQL becomes the authoritative ledger, and payouts are scoped to the API client that created them.
-- Nothing is dropped except the global idempotency-key uniqueness, which is replaced by a per-client one.

CREATE TABLE ledger_accounts (
    account_id        VARCHAR(64) PRIMARY KEY,
    available_balance BIGINT      NOT NULL,
    held_balance      BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_ledger_available_non_negative CHECK (available_balance >= 0),
    CONSTRAINT chk_ledger_held_non_negative CHECK (held_balance >= 0)
);

-- Append-only journal. Each row records the change to both balance columns and the result,
-- so every account balance can be re-derived and reconciled from its entries.
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64)  NOT NULL REFERENCES ledger_accounts (account_id),
    payout_id       UUID         REFERENCES payouts (id),
    entry_type      VARCHAR(20)  NOT NULL,
    available_delta BIGINT       NOT NULL,
    held_delta      BIGINT       NOT NULL,
    available_after BIGINT       NOT NULL,
    held_after      BIGINT       NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ledger_entries_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('OPENING_BALANCE', 'ADJUSTMENT', 'HOLD', 'RELEASE', 'SETTLE')),
    CONSTRAINT chk_ledger_entry_payout CHECK ((entry_type IN ('HOLD', 'RELEASE', 'SETTLE')) = (payout_id IS NOT NULL))
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, id);
-- At most one hold per payout, and at most one terminal action (a release OR a settlement):
-- a completed payout can never also be refunded, enforced by the database.
CREATE UNIQUE INDEX ux_ledger_entries_payout_hold ON ledger_entries (payout_id) WHERE entry_type = 'HOLD';
CREATE UNIQUE INDEX ux_ledger_entries_payout_final ON ledger_entries (payout_id) WHERE entry_type IN ('RELEASE', 'SETTLE');

-- Payouts belong to an API client; idempotency keys are unique per client instead of globally.
-- Existing rows are attributed to the client 'legacy'.
ALTER TABLE payouts
    ADD COLUMN client_id          VARCHAR(64) NOT NULL DEFAULT 'legacy',
    ADD COLUMN provider_reference VARCHAR(100);
ALTER TABLE payouts ALTER COLUMN client_id DROP DEFAULT;

ALTER TABLE payouts DROP CONSTRAINT payouts_idempotency_key_key;
ALTER TABLE payouts ADD CONSTRAINT uq_payouts_client_idempotency_key UNIQUE (client_id, idempotency_key);

CREATE INDEX idx_payouts_recipient_id ON payouts (recipient_id);
