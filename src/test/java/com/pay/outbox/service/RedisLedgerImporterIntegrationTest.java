package com.pay.outbox.service;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.service.gateway.GatewayOutcome;
import com.pay.outbox.worker.PaymentProcessingWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Migration of the pre-V4 Redis ledger. Builds the state the old implementation leaves behind
 * (Redis balances already net of holds, hold markers, payouts in every funds state) and checks that
 * the import preserves every unit of money and is safe to repeat.
 */
class RedisLedgerImporterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RedisLedgerImporter importer;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private PayoutRecoveryService recoveryService;

    @Autowired
    private PaymentProcessingWorker worker;

    private UUID legacyPayout(String account, long amount, String status, String fundsStatus, String marker) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payouts (id, recipient_id, amount, currency, status, idempotency_key, created_at,
                                     updated_at, attempts, funds_status, client_id)
                VALUES (?, ?, ?, 'INR', ?, ?, now(), now(), 0, ?, 'legacy')""",
                id, account, amount, status, "legacy-" + id, fundsStatus);
        if (marker != null) {
            redisTemplate.opsForHash().put("ledger:hold:" + id, "state", marker);
            redisTemplate.opsForHash().put("ledger:hold:" + id, "amount", String.valueOf(amount));
        }
        return id;
    }

    @Test
    void importsBalancesAndInFlightHoldsWithoutLosingMoney() {
        // Redis balances are net of holds, as the old implementation debited them on hold.
        redisTemplate.opsForValue().set("ledger:legacy_a", "700");
        redisTemplate.opsForValue().set("ledger:legacy_b", "300");
        UUID pendingNoMarker = legacyPayout("legacy_a", 100, "PENDING", "LEGACY_HELD", null);
        UUID pendingHeld = legacyPayout("legacy_a", 50, "PENDING", "HELD", "HELD");
        UUID refundOwed = legacyPayout("legacy_a", 30, "FAILED", "RELEASE_PENDING", "HELD");
        UUID refundDone = legacyPayout("legacy_a", 20, "FAILED", "RELEASE_PENDING", "RELEASED");
        UUID settlePending = legacyPayout("legacy_a", 40, "COMPLETED", "SETTLE_PENDING", "HELD");
        UUID abandonedHeld = legacyPayout("legacy_a", 10, "RESERVING", "NONE", "HELD");
        UUID abandonedEmpty = legacyPayout("legacy_a", 5, "RESERVING", "NONE", null);
        UUID completedEarlier = legacyPayout("legacy_a", 60, "COMPLETED", "SETTLED", null);
        // An account whose balance key is gone but which still has funds held for a payout.
        UUID orphanHold = legacyPayout("legacy_c", 25, "PROCESSING", "HELD", "HELD");

        RedisLedgerImporter.ImportReport report = importer.importLegacyLedger();

        assertThat(report.imported()).containsExactlyInAnyOrder("legacy_a", "legacy_b", "legacy_c");
        assertThat(report.conflicts()).isEmpty();

        // legacy_a: available 700, held 100 + 50 + 30 + 10 for the payouts still holding funds.
        LedgerService.AccountBalance a = ledgerService.getAccount("legacy_a");
        assertThat(a.available()).isEqualTo(700);
        assertThat(a.held()).isEqualTo(190);
        assertThat(ledgerService.getAccount("legacy_b")).isEqualTo(new LedgerService.AccountBalance("legacy_b", 300, 0));
        assertThat(ledgerService.getAccount("legacy_c")).isEqualTo(new LedgerService.AccountBalance("legacy_c", 0, 25));

        assertFunds(pendingNoMarker, PayoutStatus.PENDING, FundsStatus.HELD);
        assertFunds(pendingHeld, PayoutStatus.PENDING, FundsStatus.HELD);
        assertFunds(refundOwed, PayoutStatus.FAILED, FundsStatus.RELEASE_PENDING);
        assertFunds(refundDone, PayoutStatus.FAILED, FundsStatus.RELEASED);
        assertFunds(settlePending, PayoutStatus.COMPLETED, FundsStatus.SETTLED);
        assertFunds(abandonedHeld, PayoutStatus.CANCELLED, FundsStatus.RELEASE_PENDING);
        assertFunds(abandonedEmpty, PayoutStatus.CANCELLED, FundsStatus.RELEASED);
        assertFunds(completedEarlier, PayoutStatus.COMPLETED, FundsStatus.SETTLED);
        assertFunds(orphanHold, PayoutStatus.PROCESSING, FundsStatus.HELD);
        assertThat(payout(abandonedHeld).getFailureReason()).isEqualTo("RESERVATION_ABANDONED");

        // Nothing in Redis was deleted: balance keys are renamed, hold markers untouched.
        assertThat(redisTemplate.hasKey("ledger:legacy_a")).isFalse();
        assertThat(redisTemplate.opsForValue().get("ledger-migrated:legacy_a")).isEqualTo("700");
        assertThat(redisTemplate.opsForHash().get("ledger:hold:" + pendingHeld, "state")).isEqualTo("HELD");
        assertThat(reconciliationService.reconcile().consistent()).isTrue();

        // Running the import again changes nothing.
        RedisLedgerImporter.ImportReport again = importer.importLegacyLedger();
        assertThat(again.imported()).isEmpty();
        assertThat(ledgerService.getAccount("legacy_a")).isEqualTo(a);

        // Owed refunds complete exactly once, and imported holds are processed normally.
        assertThat(recoveryService.completePendingReleases()).isEqualTo(2);
        assertThat(ledgerService.getAccount("legacy_a")).isEqualTo(new LedgerService.AccountBalance("legacy_a", 740, 150));

        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.DECLINED);
        payoutQueue.publish("{\"payoutId\":\"" + pendingNoMarker + "\",\"recipientId\":\"legacy_a\",\"amount\":100,\"currency\":\"INR\"}");
        worker.drainOnce();

        assertThat(ledgerService.getAccount("legacy_a")).isEqualTo(new LedgerService.AccountBalance("legacy_a", 840, 50));
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void legacyBalanceForAnAccountThatAlreadyExistsIsReportedNotOverwritten() {
        ledgerService.seedBalance("native_acct", 999);
        redisTemplate.opsForValue().set("ledger:native_acct", "123");

        RedisLedgerImporter.ImportReport report = importer.importLegacyLedger();

        assertThat(report.conflicts()).containsExactly("native_acct");
        assertThat(ledgerService.getBalance("native_acct")).isEqualTo(999);
        assertThat(redisTemplate.opsForValue().get("ledger:native_acct")).isEqualTo("123");
    }

    @Test
    void importAfterACrashBeforeRenamingOnlyRenames() {
        redisTemplate.opsForValue().set("ledger:legacy_d", "50");
        importer.importLegacyLedger();
        // Simulate a crash between the PostgreSQL commit and the Redis rename.
        redisTemplate.rename("ledger-migrated:legacy_d", "ledger:legacy_d");

        RedisLedgerImporter.ImportReport report = importer.importLegacyLedger();

        assertThat(report.alreadyImported()).containsExactly("legacy_d");
        assertThat(ledgerService.getBalance("legacy_d")).isEqualTo(50);
        assertThat(redisTemplate.hasKey("ledger:legacy_d")).isFalse();
    }

    private void assertFunds(UUID id, PayoutStatus status, FundsStatus funds) {
        Payout payout = payout(id);
        assertThat(payout.getStatus()).as("status of %s", id).isEqualTo(status);
        assertThat(payout.getFundsStatus()).as("funds of %s", id).isEqualTo(funds);
    }
}
