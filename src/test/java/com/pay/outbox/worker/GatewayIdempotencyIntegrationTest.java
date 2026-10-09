package com.pay.outbox.worker;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.scheduler.OutboxRelayScheduler;
import com.pay.outbox.service.PayoutRecoveryService;
import com.pay.outbox.service.gateway.GatewayOutcome;
import com.pay.outbox.service.gateway.PaymentInstruction;
import com.pay.outbox.service.gateway.PaymentProvider;
import com.pay.outbox.service.gateway.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every provider request for a payout must carry the same idempotency key, and a repeated request
 * must never execute a second payment.
 */
class GatewayIdempotencyIntegrationTest extends BaseIntegrationTest {

    private static final long INITIAL_BALANCE = 100_000L;
    private static final long AMOUNT = 30_000L;

    @Autowired
    private PaymentProcessingWorker worker;

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PayoutRecoveryService recoveryService;

    @Autowired
    private PaymentProvider paymentProvider;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, INITIAL_BALANCE);
    }

    private UUID createAndRelay(String key) {
        UUID id = createPayout(key, AMOUNT);
        relay.relay();
        return id;
    }

    @Test
    void everyRetryForAPayoutUsesTheSameIdempotencyKey() {
        when(gatewayOutcomes.next(any()))
                .thenReturn(GatewayOutcome.TIMEOUT, GatewayOutcome.TIMEOUT, GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("gateway-key-retries");

        worker.drainOnce();

        ArgumentCaptor<PaymentInstruction> requests = ArgumentCaptor.forClass(PaymentInstruction.class);
        verify(gatewayOutcomes, times(3)).next(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(PaymentInstruction::idempotencyKey)
                .containsOnly("payout-" + id);
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
    }

    @Test
    void retryAfterALostResponseReturnsTheOriginalPaymentInsteadOfPayingAgain() {
        // The provider executes the payment but the response is lost; the retry must not pay again.
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID id = createAndRelay("gateway-key-lost-response");

        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getProviderReference()).startsWith("sim_");
        verify(gatewayOutcomes, times(1)).next(any()); // the retry was answered from the stored result
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(ledgerEntries(id, "SETTLE")).isEqualTo(1);
    }

    @Test
    void redeliveryAfterAWorkerCrashDoesNotPayTwice() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        UUID id = createAndRelay("gateway-key-crash");
        // A worker claimed the payout and the provider executed the payment, then the worker died
        // before recording the outcome.
        payoutQueue.claimNext(clock.instant());
        payoutRepository.claim(id, clock.instant().plusSeconds(60), clock.instant(), 5);
        ProviderResponse executed = paymentProvider.submit(
                PaymentInstruction.forPayout(id, ACCOUNT, AMOUNT, "INR"));

        clock.advance(Duration.ofMinutes(3));
        recoveryService.recoverAbandonedMessages(clock.instant());
        worker.drainOnce();

        Payout payout = payout(id);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getProviderReference()).isEqualTo(executed.providerReference());
        assertThat(payout.getAttempts()).isEqualTo(2);
        verify(gatewayOutcomes, times(1)).next(any());
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
    }

    @Test
    void duplicateProviderRequestsReturnTheSameResult() {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS, GatewayOutcome.DECLINED);
        PaymentInstruction instruction = PaymentInstruction.forPayout(UUID.randomUUID(), ACCOUNT, 10L, "INR");

        ProviderResponse first = paymentProvider.submit(instruction);
        ProviderResponse second = paymentProvider.submit(instruction);

        assertThat(second).isEqualTo(first);
        assertThat(first.status()).isEqualTo(PayoutStatus.COMPLETED);
        verify(gatewayOutcomes, times(1)).next(any());
        assertThat(paymentProvider.lookup(instruction.idempotencyKey())).contains(first);
    }

    @Test
    void payoutWhoseProviderRequestIsStillInFlightIsNeitherFailedNorRefunded() {
        UUID id = createAndRelay("gateway-key-in-flight");
        String providerKey = "gateway:sim:payment:payout-" + id;
        // An earlier request for this payout is still being processed by the provider.
        redisTemplate.opsForValue().set(providerKey, "IN_PROGRESS");

        worker.drainOnce();

        verify(gatewayOutcomes, never()).next(any());
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(held()).isEqualTo(AMOUNT);
        assertThat(payoutQueue.processingCount()).isEqualTo(1);

        // The provider finishes the earlier request; after the lease and visibility timeout the
        // payout is redelivered and resolved from that result without paying again.
        redisTemplate.opsForValue().set(providerKey, "COMPLETED|sim_earlier_request");
        clock.advance(Duration.ofMinutes(3));
        recoveryService.recoverAbandonedMessages(clock.instant());
        worker.drainOnce();

        verify(gatewayOutcomes, never()).next(any());
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout(id).getProviderReference()).isEqualTo("sim_earlier_request");
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(held()).isZero();
    }
}
