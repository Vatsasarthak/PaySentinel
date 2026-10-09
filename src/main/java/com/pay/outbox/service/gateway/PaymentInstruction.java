package com.pay.outbox.service.gateway;

import java.util.UUID;

/**
 * A request to a payment provider. {@code idempotencyKey} is derived from the payout ID and is
 * identical for every retry, redelivery and worker, so the provider executes the payment at most once.
 */
public record PaymentInstruction(String idempotencyKey, UUID payoutId, String recipientId, long amount,
                                 String currency) {

    public static String idempotencyKeyFor(UUID payoutId) {
        return "payout-" + payoutId;
    }

    public static PaymentInstruction forPayout(UUID payoutId, String recipientId, long amount, String currency) {
        return new PaymentInstruction(idempotencyKeyFor(payoutId), payoutId, recipientId, amount, currency);
    }
}
