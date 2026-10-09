package com.pay.outbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PayoutMetrics {

    private final Counter initiatedCounter;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter hangCounter;
    private final Counter rejectedCounter;
    private final Counter refundedCounter;
    private final Counter deadLetteredCounter;
    private final Counter duplicateCounter;
    private final Counter ledgerFailureCounter;
    private final Counter ledgerInconsistencyCounter;
    private final Counter relayedCounter;
    private final Counter relayFailureCounter;
    private final Counter requeuedCounter;
    private final Counter recoveredCounter;
    private final Counter rateLimitedCounter;
    private final Counter rateLimiterErrorCounter;
    private final Counter gatewayReplayCounter;
    private final Counter ledgerImportCounter;
    private final AtomicInteger reconciliationMismatches = new AtomicInteger();
    private final MeterRegistry registry;

    public PayoutMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.initiatedCounter = counter(registry, "payout.initiated", "Payouts accepted with funds held");
        this.successCounter = counter(registry, "payout.completed", "Payouts completed successfully");
        this.failedCounter = counter(registry, "payout.failed", "Payouts that failed");
        this.hangCounter = counter(registry, "payout.hang", "Gateway calls that hung and were retried");
        this.rejectedCounter = counter(registry, "payout.rejected", "Payouts rejected for insufficient balance");
        this.refundedCounter = counter(registry, "payout.refunded", "Ledger holds returned to the account");
        this.deadLetteredCounter = counter(registry, "payout.dead_lettered", "Messages moved to the dead-letter queue");
        this.duplicateCounter = counter(registry, "payout.duplicate_skipped", "Duplicate deliveries skipped");
        this.ledgerFailureCounter = counter(registry, "payout.ledger.failures",
                "Ledger actions that failed and were left for reconciliation");
        this.ledgerInconsistencyCounter = counter(registry, "payout.ledger.inconsistencies",
                "Ledger states that contradict the payout state and need manual review");
        this.relayedCounter = counter(registry, "outbox.relayed", "Outbox events published to the queue");
        this.relayFailureCounter = counter(registry, "outbox.relay.failures", "Outbox publish attempts that failed");
        this.requeuedCounter = counter(registry, "payout.queue.requeued", "Abandoned messages returned to the queue");
        this.recoveredCounter = counter(registry, "payout.recovered", "Payouts repaired by the recovery job");
        this.rateLimiterErrorCounter = counter(registry, "ratelimit.errors",
                "Rate-limit checks that failed (request allowed when fail-open)");
        this.rateLimitedCounter = counter(registry, "ratelimit.rejected", "Requests rejected with 429");
        this.gatewayReplayCounter = counter(registry, "gateway.idempotent_replays",
                "Provider requests answered from an earlier request with the same idempotency key");
        this.ledgerImportCounter = counter(registry, "ledger.redis_import.accounts",
                "Legacy Redis ledger accounts imported into PostgreSQL");
        Gauge.builder("ledger.reconciliation.mismatches", reconciliationMismatches, AtomicInteger::get)
                .description("Inconsistencies found by the last ledger reconciliation")
                .register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void incrementInitiated() { initiatedCounter.increment(); }
    public void incrementCompleted() { successCounter.increment(); }
    public void incrementFailed() { failedCounter.increment(); }
    public void incrementHang() { hangCounter.increment(); }
    public void incrementRejected() { rejectedCounter.increment(); }
    public void incrementRefunded() { refundedCounter.increment(); }
    public void incrementDeadLettered() { deadLetteredCounter.increment(); }
    public void incrementDuplicateSkipped() { duplicateCounter.increment(); }
    public void incrementLedgerFailure() { ledgerFailureCounter.increment(); }
    public void incrementLedgerInconsistency() { ledgerInconsistencyCounter.increment(); }
    public void incrementRelayed(int count) { relayedCounter.increment(count); }
    public void incrementRelayFailure() { relayFailureCounter.increment(); }
    public void incrementRequeued(int count) { requeuedCounter.increment(count); }
    public void incrementRecovered() { recoveredCounter.increment(); }
    public void incrementRateLimited(String policy) {
        rateLimitedCounter.increment();
        registry.counter("ratelimit.rejected.by_policy", "policy", policy).increment();
    }
    public void incrementRateLimiterError() { rateLimiterErrorCounter.increment(); }
    public void incrementGatewayReplay() { gatewayReplayCounter.increment(); }
    public void incrementLedgerImport() { ledgerImportCounter.increment(); }
    public void setReconciliationMismatches(int count) { reconciliationMismatches.set(count); }
}
