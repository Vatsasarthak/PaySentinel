package com.pay.outbox.service.gateway;

import java.util.Optional;

/**
 * Contract for a payment provider integration. Implementations must honour
 * {@link PaymentInstruction#idempotencyKey()}: repeating a request with the same key returns the
 * original result and never executes a second payment.
 */
public interface PaymentProvider {

    /**
     * Submits a payment. Throws {@link com.pay.outbox.exception.PaymentHangException} when the
     * outcome is unknown (timeout) so that the caller retries with the same idempotency key.
     */
    ProviderResponse submit(PaymentInstruction instruction);

    /** Looks up the final result of an earlier request, if the provider has one. */
    Optional<ProviderResponse> lookup(String idempotencyKey);

    /** Whether a request with this key is still being processed (its outcome is not yet known). */
    boolean isInProgress(String idempotencyKey);
}
