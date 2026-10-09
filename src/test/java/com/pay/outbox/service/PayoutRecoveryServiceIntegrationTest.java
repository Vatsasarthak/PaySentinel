package com.pay.outbox.service;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.OutboxStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.scheduler.OutboxRelayScheduler;
import com.pay.outbox.service.gateway.GatewayOutcome;
import com.pay.outbox.worker.PaymentProcessingWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PayoutRecoveryServiceIntegrationTest extends BaseIntegrationTest {

    private static final long INITIAL_BALANCE = 100_000L;
    private static final long AMOUNT = 30_000L;

    @Autowired
    private PayoutRecoveryService recoveryService;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PaymentProcessingWorker worker;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, INITIAL_BALANCE);
    }

    @Test
    void pendingReleasesAreCompletedExactlyOnce() {
        UUID id = createPayout("recovery-key-refund", AMOUNT);
        // State left by the legacy import: the payout failed but its refund is still owed.
        jdbcTemplate.update("update payouts set status = 'FAILED', funds_status = 'RELEASE_PENDING' where id = ?", id);

        assertThat(recoveryService.completePendingReleases()).isEqualTo(1);
        assertThat(payout(id).getFundsStatus()).isEqualTo(FundsStatus.RELEASED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);

        assertThat(recoveryService.completePendingReleases()).isZero();
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(ledgerEntries(id, "RELEASE")).isEqualTo(1);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void stuckPayoutWhoseMessageWasLostIsRepublished() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID lost = createPayout("recovery-key-lost", AMOUNT);
        UUID waiting = createPayout("recovery-key-waiting", 1_000L);
        relay.relay();
        // Redis lost the first message (e.g. data loss on restart); the second is still queued.
        redisTemplate.opsForList().remove(PayoutQueue.QUEUE_KEY, 1, payloadOf(lost));

        assertThat(recoveryService.rearmStuckPayouts(clock.instant())).isZero(); // not stuck yet
        clock.advance(Duration.ofMinutes(11));
        assertThat(recoveryService.rearmStuckPayouts(clock.instant())).isEqualTo(1);
        assertThat(outboxEventRepository.findFirstByPayoutId(lost).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEventRepository.findFirstByPayoutId(waiting).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.RELAYED);

        relay.relay();
        worker.drainOnce();

        assertThat(payout(lost).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout(waiting).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT - 1_000L);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }
}
