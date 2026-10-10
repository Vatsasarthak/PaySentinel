package com.pay.outbox.worker;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.scheduler.OutboxRelayScheduler;
import com.pay.outbox.service.LedgerReconciliationService;
import com.pay.outbox.service.PayoutRecoveryService;
import com.pay.outbox.service.PayoutReviewService;
import com.pay.outbox.service.gateway.GatewayOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A payout must never be refunded while its payment might still complete. When the provider outcome
 * stays unknown, the payout is parked in MANUAL_REVIEW with its funds held.
 */
class UncertainProviderOutcomeIntegrationTest extends BaseIntegrationTest {

    private static final long INITIAL_BALANCE = 100_000L;
    private static final long AMOUNT = 30_000L;

    @Autowired
    private PaymentProcessingWorker worker;

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PayoutRecoveryService recoveryService;

    @Autowired
    private PayoutReviewService reviewService;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, INITIAL_BALANCE);
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    private UUID createAndRelay(String key) {
        UUID id = createPayout(key, AMOUNT);
        relay.relay();
        return id;
    }

    private String providerKey(UUID id) {
        return "gateway:sim:payment:payout-" + id;
    }

    private void redeliver() {
        clock.advance(Duration.ofMinutes(3));
        recoveryService.recoverAbandonedMessages(clock.instant());
        worker.drainOnce();
    }

    private void assertHeldNotRefunded(UUID id) {
        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.MANUAL_REVIEW);
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.HELD);
        assertThat(payout.getFailureReason()).isEqualTo(PaymentProcessingWorker.OUTCOME_UNKNOWN);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(held()).isEqualTo(AMOUNT);
        assertThat(ledgerEntries(id, "RELEASE")).isZero();
        assertThat(ledgerEntries(id, "SETTLE")).isZero();
        assertThat(auditEvents(id.toString(), "PAYOUT_MANUAL_REVIEW_OPENED")).isEqualTo(1);
    }

    @Test
    void providerRequestThatStaysInFlightEndsInManualReviewNotARefund() {
        UUID id = createAndRelay("uncertain-key-in-flight");
        redisTemplate.opsForValue().set(providerKey(id), "IN_PROGRESS");

        worker.drainOnce();
        for (int delivery = 2; delivery <= 5; delivery++) {
            assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.PROCESSING);
            redeliver();
        }

        assertHeldNotRefunded(id);
        assertThat(payout(id).getAttempts()).isEqualTo(5);
        assertThat(payoutQueue.processingCount()).isZero(); // acknowledged; the database tracks the review
        assertThat(payoutQueue.deadLetters()).isEmpty();
        verify(gatewayOutcomes, never()).next(any());

        // Further deliveries of a stale copy change nothing.
        payoutQueue.publish(payloadOf(id));
        worker.drainOnce();
        assertHeldNotRefunded(id);
    }

    @Test
    void unreachableProviderAtExhaustionEndsInManualReview() {
        UUID id = createAndRelay("uncertain-key-unreachable");
        jdbcTemplate.update("update payouts set attempts = 5 where id = ?", id);
        doThrow(new RedisConnectionFailureException("provider unreachable"))
                .when(simulatedProvider).lookup(anyString());

        worker.drainOnce();

        assertHeldNotRefunded(id);
        assertThat(payoutQueue.deadLetters()).isEmpty();
    }

    @Test
    void exhaustedPayoutThatTheProviderPaidIsCompletedNotRefunded() {
        UUID id = createAndRelay("uncertain-key-paid");
        jdbcTemplate.update("update payouts set attempts = 5 where id = ?", id);
        // A previous worker's request was executed by the provider, then the worker kept crashing.
        redisTemplate.opsForValue().set(providerKey(id), "COMPLETED|sim_paid_earlier");

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.SETTLED);
        assertThat(payout.getProviderReference()).isEqualTo("sim_paid_earlier");
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(ledgerEntries(id, "RELEASE")).isZero();
        assertThat(payoutQueue.deadLetters()).isEmpty();
    }

    @Test
    void exhaustedPayoutThatTheProviderDeclinedIsRefunded() {
        UUID id = createAndRelay("uncertain-key-declined");
        jdbcTemplate.update("update payouts set attempts = 5 where id = ?", id);
        redisTemplate.opsForValue().set(providerKey(id), "FAILED|");

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).isEqualTo("DECLINED");
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(payoutQueue.deadLetters()).containsExactly(payloadOf(id));
    }

    @Test
    void reviewIsResolvedAutomaticallyOnlyByADefinitiveProviderAnswer() {
        UUID id = createAndRelay("uncertain-key-recheck");
        jdbcTemplate.update("update payouts set attempts = 5 where id = ?", id);
        redisTemplate.opsForValue().set(providerKey(id), "IN_PROGRESS");
        worker.drainOnce();
        assertHeldNotRefunded(id);

        // Still in flight, then "not found" (e.g. the provider purged it): neither is definitive.
        assertThat(reviewService.recheckWithProvider()).isZero();
        redisTemplate.delete(providerKey(id));
        assertThat(recoveryService.recheckManualReviews()).isZero();
        assertHeldNotRefunded(id);

        // The provider reports the payment as completed: settle, never refund.
        redisTemplate.opsForValue().set(providerKey(id), "COMPLETED|sim_settled_later");
        assertThat(recoveryService.recheckManualReviews()).isEqualTo(1);

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getProviderReference()).isEqualTo("sim_settled_later");
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(held()).isZero();
        assertThat(auditEvents(id.toString(), "PAYOUT_REVIEW_COMPLETED")).isEqualTo(1);
        assertThat(recoveryService.recheckManualReviews()).isZero();
    }

    @Test
    void lostResponseWithinTheRetryBudgetIsStillResolvedWithoutReview() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID id = createAndRelay("uncertain-key-lost-response");

        worker.drainOnce();

        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
    }
}
