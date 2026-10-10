package com.pay.outbox.service;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.service.gateway.GatewayOutcome;
import com.pay.outbox.worker.PaymentProcessingWorker;
import com.pay.outbox.config.PayoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Autowired
    private PayoutProperties properties;

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

    @Test
    void importInterruptedMidAccountRollsBackThatAccountAndResumesOnRestart() {
        redisTemplate.opsForValue().set("ledger:legacy_e", "100");
        redisTemplate.opsForValue().set("ledger:legacy_f", "400");
        UUID held = legacyPayout("legacy_f", 60, "PENDING", "LEGACY_HELD", null);
        // Simulate a crash while legacy_f is being imported (after its opening balance is written).
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_hold_insert() RETURNS trigger AS $$
                BEGIN IF NEW.entry_type = 'HOLD' THEN RAISE EXCEPTION 'simulated crash during import'; END IF;
                RETURN NEW; END; $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_hold BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION fail_hold_insert()""");
        try {
            assertThatThrownBy(importer::importLegacyLedger).hasMessageContaining("simulated crash");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_hold ON ledger_entries");
        }

        // legacy_e (imported first) is complete; legacy_f was rolled back entirely and left untouched.
        assertThat(ledgerService.getBalance("legacy_e")).isEqualTo(100);
        assertThat(ledgerService.getAccount("legacy_f")).isNull();
        assertThat(redisTemplate.opsForValue().get("ledger:legacy_f")).isEqualTo("400");
        assertFunds(held, PayoutStatus.PENDING, FundsStatus.LEGACY_HELD);
        assertThat(importer.unmigratedAccounts()).containsExactly("legacy_f");
        assertThatThrownBy(importer::verifyNoUnmigratedLegacyData).hasMessageContaining("legacy_f");

        // A restart resumes and completes the import.
        RedisLedgerImporter.ImportReport report = importer.importLegacyLedger();

        assertThat(report.imported()).containsExactly("legacy_f");
        assertThat(ledgerService.getAccount("legacy_f")).isEqualTo(new LedgerService.AccountBalance("legacy_f", 400, 60));
        assertFunds(held, PayoutStatus.PENDING, FundsStatus.HELD);
        assertThat(importer.unmigratedAccounts()).isEmpty();
        importer.verifyNoUnmigratedLegacyData();
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void startupIsRefusedWhileLegacyDataCannotBeImported() {
        ledgerService.seedBalance("native_acct", 999);
        redisTemplate.opsForValue().set("ledger:native_acct", "123");

        assertThatThrownBy(importer::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("native_acct");

        properties.getLedger().setFailOnUnmigratedLegacyData(false);
        try {
            importer.afterSingletonsInstantiated(); // logs instead of failing, for investigation only
        } finally {
            properties.getLedger().setFailOnUnmigratedLegacyData(true);
        }
        assertThat(ledgerService.getBalance("native_acct")).isEqualTo(999);
    }

    @Test
    void startupIsRefusedWhenTheImportIsDisabledButLegacyDataExists() {
        redisTemplate.opsForValue().set("ledger:legacy_g", "10");
        properties.getLedger().setRedisImportEnabled(false);
        try {
            assertThatThrownBy(importer::afterSingletonsInstantiated).hasMessageContaining("legacy_g");
        } finally {
            properties.getLedger().setRedisImportEnabled(true);
        }
        assertThat(ledgerService.getAccount("legacy_g")).isNull();
    }

    private void assertFunds(UUID id, PayoutStatus status, FundsStatus funds) {
        Payout payout = payout(id);
        assertThat(payout.getStatus()).as("status of %s", id).isEqualTo(status);
        assertThat(payout.getFundsStatus()).as("funds of %s", id).isEqualTo(funds);
    }
}
