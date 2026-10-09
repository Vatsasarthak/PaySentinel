package com.pay.outbox.domain.enums;

/**
 * Bookkeeping for the Redis ledger hold that belongs to a payout. The *_PENDING values mean a
 * ledger action is owed and will be retried by the reconciler until it is confirmed.
 */
public enum FundsStatus {
    NONE,
    /** Held by a pre-migration release that has no per-payout hold marker yet. */
    LEGACY_HELD,
    HELD,
    SETTLE_PENDING,
    SETTLED,
    RELEASE_PENDING,
    RELEASED
}
