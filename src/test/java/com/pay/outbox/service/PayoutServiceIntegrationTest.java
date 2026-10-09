package com.pay.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.OutboxEvent;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.OutboxStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.dto.PayoutMessage;
import com.pay.outbox.dto.PayoutRequest;
import com.pay.outbox.dto.PayoutResponse;
import com.pay.outbox.exception.IdempotencyConflictException;
import com.pay.outbox.exception.InsufficientBalanceException;
import com.pay.outbox.exception.InvalidRequestException;
import com.pay.outbox.exception.PayoutNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutServiceIntegrationTest extends BaseIntegrationTest {

    private static final long INITIAL_BALANCE = 1_000_000L;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, INITIAL_BALANCE);
    }

    @Test
    void shouldCreatePayoutOutboxEventAndHoldInOneTransaction() throws Exception {
        PayoutResponse response = payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-001", 50_000L));

        assertThat(response.getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(response.getFundsStatus()).isEqualTo(FundsStatus.HELD);
        assertThat(response.getAmount()).isEqualTo(50_000L);

        Payout payout = payout(response.getId());
        assertThat(payout.getIdempotencyKey()).isEqualTo("test-idempotency-001");
        assertThat(payout.getClientId()).isEqualTo(CLIENT_ID);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getPayoutId()).isEqualTo(response.getId());
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("PAYOUT_INITIATED");
        PayoutMessage message = objectMapper.readValue(outboxEvents.get(0).getPayload(), PayoutMessage.class);
        assertThat(message).isEqualTo(new PayoutMessage(response.getId(), ACCOUNT, 50_000L, "INR"));

        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 50_000L);
        assertThat(held()).isEqualTo(50_000L);
        assertThat(ledgerEntries(response.getId(), "HOLD")).isEqualTo(1);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void shouldReturnExistingPayoutOnIdenticalRetry() {
        PayoutResponse first = payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-002", 5_000L));
        PayoutResponse second = payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-002", 5_000L));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(payoutRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 5_000L);
    }

    @Test
    void shouldRejectIdempotencyKeyReuseWithDifferentPayload() {
        payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-003", 5_000L));

        PayoutRequest different = request("test-idempotency-003", 7_000L);
        assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, different))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting(e -> ((IdempotencyConflictException) e).isInProgress()).isEqualTo(false);
        assertThat(payoutRepository.count()).isEqualTo(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 5_000L);
    }

    @Test
    void idempotencyKeysAreScopedPerClient() {
        ledgerService.seedBalance(SHARED_ACCOUNT, 10_000L);
        PayoutRequest request = request("shared-idempotency-key", 1_000L);
        request.setRecipientId(SHARED_ACCOUNT);

        UUID first = payoutService.initiatePayout(CLIENT_ID, request).getId();
        UUID second = payoutService.initiatePayout(OTHER_CLIENT_ID, request).getId();

        assertThat(first).isNotEqualTo(second);
        assertThat(ledgerService.getBalance(SHARED_ACCOUNT)).isEqualTo(8_000L);
    }

    @Test
    void shouldRejectInsufficientBalanceWithoutHoldingFunds() {
        PayoutRequest request = request("test-idempotency-004", INITIAL_BALANCE + 1);

        assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, request))
                .isInstanceOf(InsufficientBalanceException.class);

        Payout rejected = payoutRepository.findByClientIdAndIdempotencyKey(CLIENT_ID, "test-idempotency-004")
                .orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(PayoutStatus.REJECTED);
        assertThat(rejected.getFundsStatus()).isEqualTo(FundsStatus.NONE);
        assertThat(ledgerEntries(rejected.getId(), "HOLD")).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);

        // Replaying the same request reproduces the same result.
        assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, request))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(payoutRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectUnknownRecipientAccount() {
        PayoutRequest request = request("test-idempotency-005", 100L);
        request.setRecipientId("no_such_account");

        assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, request))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void shouldRejectUnsupportedCurrencyBeforeCreatingAnything() {
        PayoutRequest request = request("test-idempotency-006", 100L);
        request.setCurrency("USD");

        assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, request))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(payoutRepository.count()).isZero();
    }

    @Test
    void shouldThrowWhenPayoutNotFound() {
        assertThatThrownBy(() -> payoutService.getPayoutById(UUID.randomUUID()))
                .isInstanceOf(PayoutNotFoundException.class);
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyCreateOnePayoutAndHoldOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PayoutResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-concurrent", 10_000L));
            }));
        }
        start.countDown();

        UUID payoutId = null;
        for (Future<PayoutResponse> future : futures) {
            try {
                UUID id = future.get().getId();
                assertThat(payoutId == null || payoutId.equals(id)).isTrue();
                payoutId = id;
            } catch (ExecutionException e) {
                throw new AssertionError("Every concurrent duplicate should receive the original payout", e);
            }
        }
        pool.shutdown();

        assertThat(payoutRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 10_000L);
        assertThat(held()).isEqualTo(10_000L);
    }

    @Test
    void databaseFailureRollsBackPayoutHoldAndOutboxTogether() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_outbox_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END; $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()""");
        try {
            assertThatThrownBy(() -> payoutService.initiatePayout(CLIENT_ID, request("test-idempotency-dbfail", 25_000L)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_outbox ON outbox_events");
        }

        assertThat(payoutRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(held()).isZero();
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }
}
