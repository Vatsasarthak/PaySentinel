package com.pay.outbox.service;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.service.LedgerService.HoldResult;
import com.pay.outbox.service.LedgerService.ReleaseResult;
import com.pay.outbox.service.LedgerService.SettleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIntegrationTest extends BaseIntegrationTest {

    private static final String LEDGER_ACCOUNT = "ledger_test_user";

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(LEDGER_ACCOUNT, 100_000L);
    }

    /** Ledger holds reference a payout row (foreign key), so tests create one per hold. */
    private UUID payoutRow(long amount) {
        return payoutRepository.saveAndFlush(Payout.builder()
                .clientId(CLIENT_ID).recipientId(LEDGER_ACCOUNT).amount(amount).currency("INR")
                .idempotencyKey("ledger-" + UUID.randomUUID()).status(PayoutStatus.PENDING)
                .fundsStatus(FundsStatus.NONE).attempts(0).build()).getId();
    }

    // Like the production code, each helper updates the payout's funds status in the same transaction
    // as the ledger change, so reconciliation can compare payouts with their entries.
    private HoldResult hold(UUID payoutId, long amount) {
        return tx.execute(s -> withFunds(payoutId, ledgerService.hold(LEDGER_ACCOUNT, payoutId, amount),
                HoldResult.HELD, "HELD"));
    }

    private ReleaseResult release(UUID payoutId) {
        return tx.execute(s -> withFunds(payoutId, ledgerService.release(payoutId), ReleaseResult.RELEASED,
                "RELEASED"));
    }

    private SettleResult settle(UUID payoutId) {
        return tx.execute(s -> withFunds(payoutId, ledgerService.settle(payoutId), SettleResult.SETTLED, "SETTLED"));
    }

    private <R> R withFunds(UUID payoutId, R result, R applied, String fundsStatus) {
        if (result == applied) {
            jdbcTemplate.update("update payouts set funds_status = ? where id = ?", fundsStatus, payoutId);
        }
        return result;
    }

    private LedgerService.AccountBalance account() {
        return ledgerService.getAccount(LEDGER_ACCOUNT);
    }

    @Test
    void holdMovesFundsFromAvailableToHeld() {
        assertThat(hold(payoutRow(30_000L), 30_000L)).isEqualTo(HoldResult.HELD);

        assertThat(account().available()).isEqualTo(70_000L);
        assertThat(account().held()).isEqualTo(30_000L);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void repeatedHoldForTheSamePayoutDebitsOnce() {
        UUID payoutId = payoutRow(30_000L);
        hold(payoutId, 30_000L);

        assertThat(hold(payoutId, 30_000L)).isEqualTo(HoldResult.HELD);
        assertThat(account().available()).isEqualTo(70_000L);
        assertThat(ledgerEntries(payoutId, "HOLD")).isEqualTo(1);
    }

    @Test
    void holdIsRejectedWhenBalanceIsInsufficientOrAccountUnknown() {
        assertThat(hold(payoutRow(999_999L), 999_999L)).isEqualTo(HoldResult.INSUFFICIENT_BALANCE);
        assertThat(tx.<HoldResult>execute(s -> ledgerService.hold("unknown_user", payoutRow(1L), 1L)))
                .isEqualTo(HoldResult.ACCOUNT_NOT_FOUND);
        assertThat(account().available()).isEqualTo(100_000L);
        assertThat(account().held()).isZero();
    }

    @Test
    void ledgerOperationsRequireAnEnclosingTransaction() {
        assertThatThrownBy(() -> ledgerService.hold(LEDGER_ACCOUNT, payoutRow(1L), 1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void releaseRefundsExactlyOnce() {
        UUID payoutId = payoutRow(30_000L);
        hold(payoutId, 30_000L);

        assertThat(release(payoutId)).isEqualTo(ReleaseResult.RELEASED);
        assertThat(release(payoutId)).isEqualTo(ReleaseResult.ALREADY_RELEASED);
        assertThat(account().available()).isEqualTo(100_000L);
        assertThat(account().held()).isZero();
        assertThat(ledgerEntries(payoutId, "RELEASE")).isEqualTo(1);
    }

    @Test
    void releaseWithoutHoldChangesNothing() {
        assertThat(release(payoutRow(10L))).isEqualTo(ReleaseResult.NOTHING_HELD);
        assertThat(account().available()).isEqualTo(100_000L);
    }

    @Test
    void settledPayoutCanNeverBeReleased() {
        UUID payoutId = payoutRow(30_000L);
        hold(payoutId, 30_000L);

        assertThat(settle(payoutId)).isEqualTo(SettleResult.SETTLED);
        assertThat(settle(payoutId)).isEqualTo(SettleResult.ALREADY_SETTLED);
        assertThat(release(payoutId)).isEqualTo(ReleaseResult.SETTLED_CONFLICT);
        assertThat(account().available()).isEqualTo(70_000L);
        assertThat(account().held()).isZero();

        // The database itself refuses a second terminal entry for the payout.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (account_id, payout_id, entry_type, available_delta, held_delta,
                       available_after, held_after, idempotency_key, created_at)
                VALUES (?, ?, 'RELEASE', 30000, -30000, 100000, 0, 'forged-release', now())""",
                LEDGER_ACCOUNT, payoutId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void releasedPayoutCanNeverBeSettled() {
        UUID payoutId = payoutRow(30_000L);
        hold(payoutId, 30_000L);
        release(payoutId);

        assertThat(settle(payoutId)).isEqualTo(SettleResult.RELEASED_CONFLICT);
        assertThat(account().available()).isEqualTo(100_000L);
    }

    @Test
    void concurrentHoldsNeverOverdrawTheAccount() throws Exception {
        ledgerService.seedBalance(LEDGER_ACCOUNT, 1_000L);
        List<UUID> payouts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            payouts.add(payoutRow(100L));
        }

        List<HoldResult> results = runConcurrently(payouts.stream()
                .<Callable<HoldResult>>map(id -> () -> hold(id, 100L)).toList());

        assertThat(results).filteredOn(r -> r == HoldResult.HELD).hasSize(10);
        assertThat(results).filteredOn(r -> r == HoldResult.INSUFFICIENT_BALANCE).hasSize(15);
        assertThat(account().available()).isZero();
        assertThat(account().held()).isEqualTo(1_000L);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void concurrentReleasesOfOnePayoutRefundOnce() throws Exception {
        UUID payoutId = payoutRow(30_000L);
        hold(payoutId, 30_000L);

        List<ReleaseResult> results = runConcurrently(java.util.Collections.nCopies(10,
                (Callable<ReleaseResult>) () -> release(payoutId)));

        assertThat(results).filteredOn(r -> r == ReleaseResult.RELEASED).hasSize(1);
        assertThat(results).filteredOn(r -> r == ReleaseResult.ALREADY_RELEASED).hasSize(9);
        assertThat(account().available()).isEqualTo(100_000L);
        assertThat(ledgerEntries(payoutId, "RELEASE")).isEqualTo(1);
    }

    @Test
    void concurrentMixedOperationsKeepTheLedgerConsistent() throws Exception {
        List<UUID> payouts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = payoutRow(1_000L);
            hold(id, 1_000L);
            payouts.add(id);
        }
        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < payouts.size(); i++) {
            UUID id = payouts.get(i);
            work.add(i % 2 == 0 ? () -> release(id) : () -> settle(id));
            work.add(() -> hold(payoutRow(500L), 500L));
        }

        runConcurrently(work);

        // 10 released (+10,000), 10 settled (-10,000 left the account), 20 new holds of 500.
        assertThat(account().available()).isEqualTo(100_000L - 20_000L + 10_000L - 10_000L);
        assertThat(account().held()).isEqualTo(10_000L);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void reconciliationDetectsTampering() {
        hold(payoutRow(30_000L), 30_000L);
        assertThat(reconciliationService.reconcile().consistent()).isTrue();

        jdbcTemplate.update("UPDATE ledger_accounts SET available_balance = available_balance + 5 WHERE account_id = ?",
                LEDGER_ACCOUNT);

        LedgerReconciliationService.Report report = reconciliationService.reconcile();
        assertThat(report.consistent()).isFalse();
        assertThat(report.accountsWithBalanceDrift()).containsExactly(LEDGER_ACCOUNT);
    }

    @Test
    void seedingRecordsAnAdjustmentEntry() {
        ledgerService.seedBalance(LEDGER_ACCOUNT, 250L);

        assertThat(account().available()).isEqualTo(250L);
        assertThat(ledgerService.recentEntries(LEDGER_ACCOUNT, 10))
                .extracting(LedgerService.LedgerEntry::entryType).containsOnly("ADJUSTMENT");
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void unknownAccountHasNoBalance() {
        assertThat(ledgerService.getBalance("unknown_user")).isNull();
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
