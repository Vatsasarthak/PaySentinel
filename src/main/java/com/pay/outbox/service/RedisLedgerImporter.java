package com.pay.outbox.service;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.dto.ValidationPatterns;
import com.pay.outbox.metrics.PayoutMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One-time, idempotent migration of the pre-V4 Redis ledger into PostgreSQL.
 * <p>
 * For every legacy account ({@code ledger:<accountId>} in Redis, or an account referenced by a
 * payout whose funds were held by the Redis ledger) it creates the PostgreSQL account with an
 * OPENING_BALANCE entry equal to the Redis balance plus the funds still held for in-flight payouts,
 * then a HOLD entry per such payout, and normalises the payout's funds status. Whether a payout's
 * funds are still held is decided from the payout state and its Redis hold marker:
 * <ul>
 *   <li>HELD / LEGACY_HELD: held.</li>
 *   <li>RELEASE_PENDING: held only if the marker is still HELD (otherwise Redis already refunded it).</li>
 *   <li>SETTLE_PENDING: not held (the money already left); becomes SETTLED.</li>
 *   <li>RESERVING (request died mid-way): cancelled; held only if the marker is HELD, in which case
 *       the refund is completed by the recovery job.</li>
 * </ul>
 * Nothing is discarded: imported balance keys are renamed to {@code ledger-migrated:<accountId>} and
 * hold markers are left in place. The import runs before the HTTP server and scheduled jobs start.
 * Old (pre-V4) instances must be stopped first: they would keep writing balances to Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLedgerImporter implements SmartInitializingSingleton {

    static final String LEGACY_PREFIX = "ledger:";
    static final String HOLD_PREFIX = "ledger:hold:";
    static final String MIGRATED_PREFIX = "ledger-migrated:";
    private static final Pattern ACCOUNT_ID = Pattern.compile(ValidationPatterns.ACCOUNT_ID);

    public record ImportReport(List<String> imported, List<String> alreadyImported, List<String> conflicts) {
    }

    private record LegacyPayout(UUID id, String status, String fundsStatus, long amount) {
    }

    private enum Outcome { IMPORTED, ALREADY_IMPORTED, CONFLICT }

    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate jdbc;
    private final LedgerService ledgerService;
    private final TransactionTemplate transactionTemplate;
    private final PayoutProperties properties;
    private final PayoutMetrics payoutMetrics;
    private final Clock clock;

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.getLedger().isRedisImportEnabled()) {
            log.info("Legacy Redis ledger import is disabled");
            return;
        }
        ImportReport report = importLegacyLedger();
        if (!report.imported().isEmpty() || !report.conflicts().isEmpty()) {
            log.warn("Legacy Redis ledger import: imported={}, conflicts={}", report.imported(), report.conflicts());
        }
    }

    public ImportReport importLegacyLedger() {
        Map<String, Long> balances = legacyBalances();
        Set<String> accounts = new TreeSet<>(balances.keySet());
        accounts.addAll(jdbc.queryForList("""
                SELECT DISTINCT p.recipient_id FROM payouts p
                 WHERE (p.status = 'RESERVING' OR p.funds_status IN ('HELD', 'LEGACY_HELD', 'RELEASE_PENDING', 'SETTLE_PENDING'))
                   AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.payout_id = p.id)""", String.class));

        ImportReport report = new ImportReport(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (String account : accounts) {
            switch (importAccount(account, balances.get(account))) {
                case IMPORTED -> report.imported().add(account);
                case ALREADY_IMPORTED -> report.alreadyImported().add(account);
                case CONFLICT -> report.conflicts().add(account);
            }
        }
        return report;
    }

    private Outcome importAccount(String accountId, Long redisBalance) {
        String migrationKey = "MIGRATION:" + accountId;
        if (ledgerService.entryExists(migrationKey)) {
            markMigrated(accountId);
            return Outcome.ALREADY_IMPORTED;
        }
        if (ledgerService.getAccount(accountId) != null) {
            log.error("Legacy Redis ledger data for account {} was not imported because the account already exists "
                    + "in PostgreSQL; reconcile it manually (Redis key {} is left untouched)", accountId,
                    LEGACY_PREFIX + accountId);
            return Outcome.CONFLICT;
        }

        Boolean imported = transactionTemplate.execute(status -> {
            List<LegacyPayout> payouts = jdbc.query("""
                    SELECT id, status, funds_status, amount FROM payouts p
                     WHERE p.recipient_id = ?
                       AND (p.status = 'RESERVING' OR p.funds_status IN ('HELD', 'LEGACY_HELD', 'RELEASE_PENDING', 'SETTLE_PENDING'))
                       AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.payout_id = p.id)
                     ORDER BY p.created_at
                       FOR UPDATE""",
                    (rs, i) -> new LegacyPayout(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getLong(4)), accountId);

            List<LegacyPayout> held = new ArrayList<>();
            for (LegacyPayout payout : payouts) {
                if (stillHeld(payout)) {
                    held.add(payout);
                }
            }
            long heldTotal = held.stream().mapToLong(LegacyPayout::amount).sum();
            long available = redisBalance != null ? redisBalance : 0;
            if (!ledgerService.openAccount(accountId, Math.addExact(available, heldTotal), migrationKey)) {
                return false; // created concurrently by another instance's import
            }
            for (LegacyPayout payout : held) {
                if (ledgerService.hold(accountId, payout.id(), payout.amount()) != LedgerService.HoldResult.HELD) {
                    throw new IllegalStateException("Could not re-create hold for payout " + payout.id());
                }
            }
            for (LegacyPayout payout : payouts) {
                normalise(payout, held.contains(payout));
            }
            log.info("Imported legacy account {}: available {}, held {} across {} payouts", accountId, available,
                    heldTotal, held.size());
            return true;
        });
        if (!Boolean.TRUE.equals(imported)) {
            return Outcome.ALREADY_IMPORTED;
        }
        markMigrated(accountId);
        payoutMetrics.incrementLedgerImport();
        return Outcome.IMPORTED;
    }

    private boolean stillHeld(LegacyPayout payout) {
        if ("RESERVING".equals(payout.status())) {
            return "HELD".equals(holdMarker(payout.id()));
        }
        return switch (payout.fundsStatus()) {
            case "HELD", "LEGACY_HELD" -> true;
            case "RELEASE_PENDING" -> "HELD".equals(holdMarker(payout.id()));
            default -> false;
        };
    }

    private void normalise(LegacyPayout payout, boolean held) {
        String status = payout.status();
        String reason = null;
        String funds;
        if ("RESERVING".equals(status)) {
            status = "CANCELLED";
            reason = "RESERVATION_ABANDONED";
            funds = held ? "RELEASE_PENDING" : "RELEASED";
        } else {
            funds = switch (payout.fundsStatus()) {
                case "HELD", "LEGACY_HELD" -> "HELD";
                case "RELEASE_PENDING" -> held ? "RELEASE_PENDING" : "RELEASED";
                default -> "SETTLED";
            };
        }
        jdbc.update("""
                UPDATE payouts SET status = ?, funds_status = ?, failure_reason = COALESCE(?, failure_reason),
                       updated_at = ? WHERE id = ?""",
                status, funds, reason, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), payout.id());
    }

    private Map<String, Long> legacyBalances() {
        Map<String, Long> balances = new TreeMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(LEGACY_PREFIX + "*").count(1000).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key.startsWith(HOLD_PREFIX)) {
                    continue;
                }
                String accountId = key.substring(LEGACY_PREFIX.length());
                String value = redisTemplate.opsForValue().get(key);
                try {
                    long balance = Long.parseLong(value);
                    if (!ACCOUNT_ID.matcher(accountId).matches() || balance < 0) {
                        throw new NumberFormatException("invalid account id or negative balance");
                    }
                    balances.put(accountId, balance);
                } catch (NumberFormatException e) {
                    log.error("Skipping unreadable legacy ledger key {}; it is left untouched for manual review", key);
                }
            }
        }
        return balances;
    }

    private String holdMarker(UUID payoutId) {
        Object state = redisTemplate.opsForHash().get(HOLD_PREFIX + payoutId, "state");
        return state != null ? state.toString() : null;
    }

    private void markMigrated(String accountId) {
        String key = LEGACY_PREFIX + accountId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.rename(key, MIGRATED_PREFIX + accountId);
        }
    }
}
