package com.pay.outbox.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pay.outbox.metrics.PayoutMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ledger against itself and against payout state. A consistent ledger satisfies:
 * <ol>
 *   <li>each account's balances equal the sum of its entries' deltas;</li>
 *   <li>each account's held balance equals the sum of its holds that have no release or settlement;</li>
 *   <li>each payout's funds status matches its ledger entries (HELD/RELEASE_PENDING: open hold,
 *       RELEASED: hold + release, SETTLED: hold + settlement).</li>
 * </ol>
 * Payouts that never held funds through this ledger (rejected, or completed before migration V4)
 * have no entries and are not checked against entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerReconciliationService {

    private static final int MAX_REPORTED = 100;

    public record Report(List<String> accountsWithBalanceDrift, List<String> accountsWithHeldDrift,
                         List<String> payoutsWithLedgerMismatch) {
        @JsonProperty("consistent")
        public boolean consistent() {
            return accountsWithBalanceDrift.isEmpty() && accountsWithHeldDrift.isEmpty()
                    && payoutsWithLedgerMismatch.isEmpty();
        }

        @JsonProperty("mismatchCount")
        public int mismatchCount() {
            return accountsWithBalanceDrift.size() + accountsWithHeldDrift.size() + payoutsWithLedgerMismatch.size();
        }
    }

    private final JdbcTemplate jdbc;
    private final PayoutMetrics payoutMetrics;

    @Transactional(readOnly = true)
    public Report reconcile() {
        List<String> balanceDrift = jdbc.queryForList("""
                SELECT a.account_id
                  FROM ledger_accounts a
                  LEFT JOIN (SELECT account_id, SUM(available_delta) AS available, SUM(held_delta) AS held
                               FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.account_id
                 WHERE a.available_balance <> COALESCE(e.available, 0) OR a.held_balance <> COALESCE(e.held, 0)
                 ORDER BY a.account_id LIMIT ?""", String.class, MAX_REPORTED);

        List<String> heldDrift = jdbc.queryForList("""
                SELECT a.account_id
                  FROM ledger_accounts a
                  LEFT JOIN (SELECT h.account_id, SUM(-h.available_delta) AS open_holds
                               FROM ledger_entries h
                              WHERE h.entry_type = 'HOLD'
                                AND NOT EXISTS (SELECT 1 FROM ledger_entries f
                                                 WHERE f.payout_id = h.payout_id
                                                   AND f.entry_type IN ('RELEASE', 'SETTLE'))
                              GROUP BY h.account_id) o ON o.account_id = a.account_id
                 WHERE a.held_balance <> COALESCE(o.open_holds, 0)
                 ORDER BY a.account_id LIMIT ?""", String.class, MAX_REPORTED);

        List<String> payoutMismatches = new ArrayList<>(jdbc.queryForList("""
                SELECT p.id::text
                  FROM payouts p
                  JOIN ledger_entries h ON h.payout_id = p.id AND h.entry_type = 'HOLD'
                  LEFT JOIN ledger_entries f ON f.payout_id = p.id AND f.entry_type IN ('RELEASE', 'SETTLE')
                 WHERE NOT ((p.funds_status IN ('HELD', 'RELEASE_PENDING') AND f.entry_type IS NULL)
                         OR (p.funds_status = 'RELEASED' AND f.entry_type = 'RELEASE')
                         OR (p.funds_status = 'SETTLED' AND f.entry_type = 'SETTLE'))
                 LIMIT ?""", String.class, MAX_REPORTED));
        payoutMismatches.addAll(jdbc.queryForList("""
                SELECT p.id::text
                  FROM payouts p
                 WHERE p.funds_status IN ('HELD', 'RELEASE_PENDING', 'LEGACY_HELD', 'SETTLE_PENDING')
                   AND NOT EXISTS (SELECT 1 FROM ledger_entries h WHERE h.payout_id = p.id AND h.entry_type = 'HOLD')
                 LIMIT ?""", String.class, MAX_REPORTED));

        Report report = new Report(balanceDrift, heldDrift, payoutMismatches);
        payoutMetrics.setReconciliationMismatches(report.mismatchCount());
        if (!report.consistent()) {
            log.error("Ledger reconciliation found inconsistencies: {}", report);
        }
        return report;
    }
}
