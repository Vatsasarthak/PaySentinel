package com.pay.outbox.service;

import com.pay.outbox.domain.enums.PayoutStatus;
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
import java.util.UUID;

/**
 * Sends payouts to the payment provider. Every attempt for a payout (backoff retries, redeliveries
 * after a crash, other workers) uses the same idempotency key, {@code payout-<payoutId>}, so the
 * provider executes the payment at most once.
 * <p>
 * Money only moves on a definitive provider answer: a completed payment is settled, a declined or
 * never-received one is refunded. When the provider cannot say (request still in flight, or the
 * provider is unreachable), the outcome is reported as unknown and nothing is refunded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayService {

    /** What the provider knows about a payout's request. */
    public enum ProviderState {
        COMPLETED,
        FAILED,
        /** A request with this key is still being processed: the payment may yet complete. */
        IN_PROGRESS,
        /** The provider confirms it never executed a request with this key. */
        NOT_FOUND,
        /** The provider could not be asked (error or timeout): the outcome is unknown. */
        UNAVAILABLE;

        public boolean definitive() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public record ProviderCheck(ProviderState state, String providerReference) {
    }

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
     * After the last timeout, ask the provider before deciding: a final result stands; a request the
     * provider never executed is reported as exhausted (safe to refund); anything else is unknown.
     */
    @Recover
    public ProviderResponse recover(PaymentHangException e, PayoutMessage message) {
        ProviderCheck check = checkProvider(message.payoutId());
        return switch (check.state()) {
            case COMPLETED, FAILED -> {
                log.warn("Provider calls for payout {} timed out but the provider reports {}; using that result",
                        message.payoutId(), check.state());
                yield new ProviderResponse(PayoutStatus.valueOf(check.state().name()), check.providerReference());
            }
            case NOT_FOUND -> {
                log.error("All provider attempts timed out for payout {} and the provider never executed it",
                        message.payoutId());
                throw new PaymentExhaustedException("All retries exhausted for payout " + message.payoutId());
            }
            case IN_PROGRESS, UNAVAILABLE -> {
                log.warn("Outcome of payout {} is unknown (provider state {}); it will not be refunded",
                        message.payoutId(), check.state());
                throw new PaymentOutcomeUnknownException("Provider outcome for payout " + message.payoutId()
                        + " is unknown: " + check.state());
            }
        };
    }

    /** Asks the provider about a payout's request. Never throws: a failed lookup is UNAVAILABLE. */
    public ProviderCheck checkProvider(UUID payoutId) {
        String key = PaymentInstruction.idempotencyKeyFor(payoutId);
        try {
            Optional<ProviderResponse> result = paymentProvider.lookup(key);
            if (result.isPresent()) {
                return result.get().status() == PayoutStatus.COMPLETED
                        ? new ProviderCheck(ProviderState.COMPLETED, result.get().providerReference())
                        : new ProviderCheck(ProviderState.FAILED, null);
            }
            return new ProviderCheck(paymentProvider.isInProgress(key) ? ProviderState.IN_PROGRESS
                    : ProviderState.NOT_FOUND, null);
        } catch (RuntimeException e) {
            log.warn("Could not look up provider result for payout {}", payoutId, e);
            return new ProviderCheck(ProviderState.UNAVAILABLE, null);
        }
    }

    private static PaymentInstruction instructionFor(PayoutMessage message) {
        return PaymentInstruction.forPayout(message.payoutId(), message.recipientId(), message.amount(),
                message.currency());
    }
}
