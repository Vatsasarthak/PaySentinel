package com.pay.outbox.scheduler;

import com.pay.outbox.service.LedgerReconciliationService;
import com.pay.outbox.service.PayoutRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutRecoveryScheduler {

    private final PayoutRecoveryService recoveryService;
    private final LedgerReconciliationService reconciliationService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${payout.recovery.interval-ms:30000}",
            initialDelayString = "${payout.recovery.initial-delay-ms:10000}")
    @SchedulerLock(name = "payoutRecovery", lockAtMostFor = "${payout.recovery.lock-at-most-for:PT5M}")
    public void recover() {
        Instant now = clock.instant();
        runStep("abandoned message recovery", () -> recoveryService.recoverAbandonedMessages(now));
        runStep("pending refunds", recoveryService::completePendingReleases);
        runStep("stuck payout re-publication", () -> recoveryService.rearmStuckPayouts(now));
    }

    @Scheduled(fixedDelayString = "${payout.recovery.reconciliation-interval-ms:300000}",
            initialDelayString = "${payout.recovery.initial-delay-ms:10000}")
    @SchedulerLock(name = "ledgerReconciliation", lockAtMostFor = "${payout.recovery.lock-at-most-for:PT5M}")
    public void reconcileLedger() {
        runStep("ledger reconciliation", reconciliationService::reconcile);
    }

    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Recovery step '{}' failed; it will run again on the next cycle", name, e);
        }
    }
}
