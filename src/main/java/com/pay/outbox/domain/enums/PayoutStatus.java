package com.pay.outbox.domain.enums;

public enum PayoutStatus {
    /** Row created; the ledger hold is in progress. */
    RESERVING,
    /** Funds held and outbox event written; waiting for a worker. */
    PENDING,
    /** Claimed by a worker holding a lease. */
    PROCESSING,
    /**
     * The provider outcome could not be established. Funds stay held; the payout is neither completed
     * nor refunded until the provider reports a final result or a treasury operator resolves it.
     */
    MANUAL_REVIEW,
    COMPLETED,
    FAILED,
    /** Reservation abandoned before the payout was accepted; any hold is released. */
    CANCELLED,
    /** Insufficient balance or unknown account; nothing was held. */
    REJECTED
}
