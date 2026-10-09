package com.pay.outbox.exception;

/**
 * The provider has not finished a request for this payout, so its outcome is unknown. The payout
 * must be neither completed nor refunded until the provider reports a final result.
 */
public class PaymentOutcomeUnknownException extends RuntimeException {
    public PaymentOutcomeUnknownException(String message) {
        super(message);
    }
}
