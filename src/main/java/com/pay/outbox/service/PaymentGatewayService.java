package com.pay.outbox.service;

import com.pay.outbox.dto.PayoutMessage;
import com.pay.outbox.exception.PaymentExhaustedException;
import com.pay.outbox.exception.PaymentHangException;
import com.pay.outbox.exception.PaymentOutcomeUnknownException;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.service.gateway.PaymentInstruction;
import com.pay.outbox.service.gateway.PaymentProvider;
import com.pay.outbox.service.gateway.ProviderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Sends payouts to the payment provider. Every attempt for a payout (backoff retries, redeliveries
 * after a crash, other workers) uses the same idempotency key, {@code payout-<payoutId>}, so the
 * provider executes the payment at most once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayService {

    private final PaymentProvider paymentProvider;
    private final PayoutMetrics payoutMetrics;

    @Retryable(
            retryFor = PaymentHangException.class,
            maxAttemptsExpression = "${payout.gateway.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${payout.gateway.backoff-delay-ms:2000}",
                    multiplierExpression = "${payout.gateway.backoff-multiplier:2}")
    )
    public ProviderResponse processPayment(PayoutMessage message) {
        PaymentInstruction instruction = instructionFor(message);
        log.info("Calling payment provider for payout {} with idempotency key {}", message.payoutId(),
                instruction.idempotencyKey());
        try {
            ProviderResponse response = paymentProvider.submit(instruction);
            log.info("Payment provider returned {} for payout {}", response.status(), message.payoutId());
            return response;
        } catch (PaymentHangException e) {
            log.warn("Payment provider did not answer for payout {}; will retry with the same idempotency key",
                    message.payoutId());
            payoutMetrics.incrementHang();
            throw e;
        }
    }

    /**
     * After the last timeout the outcome is unknown, so ask the provider before giving up: if it did
     * execute the payment, that result stands and the payout is not refunded; if the request is still
     * in progress, the payout must not be failed (or refunded) yet.
     */
    @Recover
    public ProviderResponse recover(PaymentHangException e, PayoutMessage message) {
        String key = PaymentInstruction.idempotencyKeyFor(message.payoutId());
        Optional<ProviderResponse> known = paymentProvider.lookup(key);
        if (known.isPresent()) {
            log.warn("Provider calls for payout {} timed out but the provider reports {}; using that result",
                    message.payoutId(), known.get().status());
            return known.get();
        }
        if (paymentProvider.isInProgress(key)) {
            log.warn("Provider is still processing payout {}; leaving it unresolved for redelivery", message.payoutId());
            throw new PaymentOutcomeUnknownException("Provider request for payout " + message.payoutId()
                    + " is still in progress");
        }
        log.error("All provider attempts timed out for payout {} and the provider has no record of it",
                message.payoutId());
        throw new PaymentExhaustedException("All retries exhausted for payout " + message.payoutId());
    }

    private static PaymentInstruction instructionFor(PayoutMessage message) {
        return PaymentInstruction.forPayout(message.payoutId(), message.recipientId(), message.amount(),
                message.currency());
    }
}
