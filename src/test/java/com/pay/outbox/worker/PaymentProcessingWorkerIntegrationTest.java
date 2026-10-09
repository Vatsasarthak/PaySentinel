package com.pay.outbox.worker;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.scheduler.OutboxRelayScheduler;
import com.pay.outbox.service.LedgerReconciliationService;
import com.pay.outbox.service.LedgerService.ReleaseResult;
import com.pay.outbox.service.PayoutRecoveryService;
import com.pay.outbox.service.gateway.GatewayOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentProcessingWorkerIntegrationTest extends BaseIntegrationTest {

    private static final long INITIAL_BALANCE = 100_000L;
    private static final long AMOUNT = 30_000L;

    @Autowired
    private PaymentProcessingWorker worker;

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PayoutRecoveryService recoveryService;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private TransactionTemplate tx;

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

    private void assertQueuesEmpty() {
        assertThat(payoutQueue.queuedCount()).isZero();
        assertThat(payoutQueue.processingCount()).isZero();
        assertThat(redisTemplate.opsForHash().size("payout:processing:claims")).isZero();
    }

    @Test
    void successfulPaymentCompletesAndSettlesTheHold() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("worker-key-success");

        assertThat(worker.drainOnce()).isEqualTo(1);

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.SETTLED);
        assertThat(payout.getProviderReference()).startsWith("sim_");
        assertThat(payout.getAttempts()).isEqualTo(1);
        assertThat(payout.getLeaseUntil()).isNull();
        assertThat(ledgerEntries(id, "SETTLE")).isEqualTo(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(held()).isZero();
        assertQueuesEmpty();
    }

    @Test
    void failedPaymentRefundsExactlyOnce() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.DECLINED);
        UUID id = createAndRelay("worker-key-failure");

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).isEqualTo("DECLINED");
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.RELEASED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);

        // Neither a repeated release nor a duplicate delivery refunds again.
        assertThat(tx.<ReleaseResult>execute(s -> ledgerService.release(id))).isEqualTo(ReleaseResult.ALREADY_RELEASED);
        payoutQueue.publish(payloadOf(id));
        worker.drainOnce();

        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(ledgerEntries(id, "RELEASE")).isEqualTo(1);
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.FAILED);
        verify(gatewayOutcomes, times(1)).next(any());
        assertQueuesEmpty();
    }

    @Test
    void duplicateDeliveryAfterCompletionIsIgnoredAndNeverRefunds() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("worker-key-duplicate");
        worker.drainOnce();

        // A later duplicate whose provider call would have failed must not touch the payout.
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.DECLINED);
        payoutQueue.publish(payloadOf(id));
        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.SETTLED);
        assertThat(payout.getAttempts()).isEqualTo(1);
        assertThat(tx.<ReleaseResult>execute(s -> ledgerService.release(id))).isEqualTo(ReleaseResult.SETTLED_CONFLICT);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(ledgerEntries(id, "RELEASE")).isZero();
        verify(gatewayOutcomes, times(1)).next(any());
        assertQueuesEmpty();
    }

    @Test
    void concurrentDeliveriesOfTheSamePayoutCallTheProviderOnce() {
        when(gatewayOutcomes.next(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return GatewayOutcome.SUCCESS;
        });
        UUID id = createAndRelay("worker-key-concurrent");
        payoutQueue.publish(payloadOf(id));
        payoutQueue.publish(payloadOf(id));

        assertThat(worker.drainOnce()).isEqualTo(3); // all three copies processed in parallel

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getAttempts()).isEqualTo(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        verify(gatewayOutcomes, times(1)).next(any());
        assertQueuesEmpty();
    }

    @Test
    void messageAbandonedByACrashedWorkerIsRecoveredAndProcessed() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("worker-key-crash-before");

        // A worker claimed the message and died before doing anything else.
        assertThat(payoutQueue.claimNext(clock.instant())).isEqualTo(payloadOf(id));
        assertThat(recoveryService.recoverAbandonedMessages(clock.instant())).isZero(); // still within timeout
        assertThat(worker.drainOnce()).isZero();

        clock.advance(Duration.ofMinutes(3));
        assertThat(recoveryService.recoverAbandonedMessages(clock.instant())).isEqualTo(1);
        worker.drainOnce();

        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout(id).getAttempts()).isEqualTo(1);
        assertQueuesEmpty();
    }

    @Test
    void payoutLeasedByACrashedWorkerIsReclaimedAfterTheLeaseExpires() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("worker-key-crash-during");

        // A worker claimed both the message and the payout, then died before calling the provider.
        payoutQueue.claimNext(clock.instant());
        assertThat(payoutRepository.claim(id, clock.instant().plusSeconds(60), clock.instant(), 5)).isEqualTo(1);
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.PROCESSING);

        clock.advance(Duration.ofMinutes(3));
        recoveryService.recoverAbandonedMessages(clock.instant());
        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getAttempts()).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertQueuesEmpty();
    }

    @Test
    void exhaustedProviderRetriesFailThePayoutRefundAndDeadLetterOnce() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.TIMEOUT);
        UUID id = createAndRelay("worker-key-exhausted");

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.RELEASED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(payoutQueue.deadLetters()).containsExactly(payloadOf(id));
        verify(gatewayOutcomes, times(3)).next(any());

        // A redelivered copy neither calls the provider nor duplicates the DLQ entry.
        payoutQueue.publish(payloadOf(id));
        worker.drainOnce();

        assertThat(payoutQueue.deadLetters()).hasSize(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(ledgerEntries(id, "RELEASE")).isEqualTo(1);
        verify(gatewayOutcomes, times(3)).next(any());
        assertQueuesEmpty();
    }

    @Test
    void payoutExceedingMaxDeliveryAttemptsIsFailedAndDeadLettered() {
        UUID id = createAndRelay("worker-key-max-attempts");
        jdbcTemplate.update("update payouts set attempts = 5 where id = ?", id);

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).isEqualTo("MAX_DELIVERY_ATTEMPTS");
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.RELEASED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(payoutQueue.deadLetters()).containsExactly(payloadOf(id));
        verify(gatewayOutcomes, never()).next(any());
        assertQueuesEmpty();
    }

    @Test
    void malformedMessageIsDeadLettered() {
        payoutQueue.publish("not-json");

        worker.drainOnce();

        assertThat(payoutQueue.deadLetters()).containsExactly("not-json");
        verify(gatewayOutcomes, never()).next(any());
        assertQueuesEmpty();
    }
}
