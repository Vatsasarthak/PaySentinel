package com.pay.outbox.service;

import com.pay.outbox.exception.AccountNotFoundException;
import com.pay.outbox.exception.InsufficientBalanceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL ledger. Each account row holds an available and a held balance; every change also
 * appends a {@code ledger_entries} row with a unique idempotency key, so balances can be re-derived
 * and reconciled.
 * <p>
 * Mutations lock the account row ({@code SELECT ... FOR UPDATE}), which serialises concurrent
 * changes to the same account, and run inside the caller's transaction so that a payout's state
 * change and its ledger effect commit or roll back together. Holds, releases and settlements are
 * idempotent per payout; the database additionally rejects a second terminal entry for a payout,
 * so a settled payout can never be released.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    public enum HoldResult { HELD, ACCOUNT_NOT_FOUND, INSUFFICIENT_BALANCE }

    public enum ReleaseResult { RELEASED, ALREADY_RELEASED, NOTHING_HELD, SETTLED_CONFLICT }

    public enum SettleResult { SETTLED, ALREADY_SETTLED, NOTHING_HELD, RELEASED_CONFLICT }

    /** Balances in whole minor units of the account's currency (paise for INR). */
    public record AccountBalance(String accountId, long available, long held) {
    }

    public record LedgerEntry(long id, String accountId, UUID payoutId, String entryType, long availableDelta,
                              long heldDelta, long availableAfter, long heldAfter, String idempotencyKey,
                              Instant createdAt) {
    }

    private record Hold(String accountId, long amount) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditService auditService;

    /** Moves {@code amount} from available to held for a payout. Idempotent per payout. */
    @Transactional(propagation = Propagation.MANDATORY)
    public HoldResult hold(String accountId, UUID payoutId, long amount) {
        AccountBalance account = lockAccount(accountId);
        if (account == null) {
            return HoldResult.ACCOUNT_NOT_FOUND;
        }
        if (findHold(payoutId) != null) {
            return HoldResult.HELD;
        }
        if (account.available() < amount) {
            return HoldResult.INSUFFICIENT_BALANCE;
        }
        apply(account, payoutId, "HOLD", -amount, amount, "HOLD:" + payoutId);
        return HoldResult.HELD;
    }

    /** Returns a payout's held funds to the available balance, at most once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReleaseResult release(UUID payoutId) {
        Hold hold = findHold(payoutId);
        if (hold == null) {
            return ReleaseResult.NOTHING_HELD;
        }
        AccountBalance account = lockAccount(hold.accountId());
        String finalEntry = findFinalEntryType(payoutId);
        if ("RELEASE".equals(finalEntry)) {
            return ReleaseResult.ALREADY_RELEASED;
        }
        if ("SETTLE".equals(finalEntry)) {
            return ReleaseResult.SETTLED_CONFLICT;
        }
        apply(account, payoutId, "RELEASE", hold.amount(), -hold.amount(), "RELEASE:" + payoutId);
        return ReleaseResult.RELEASED;
    }

    /** Removes a completed payout's held funds from the account (the money has left), at most once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettleResult settle(UUID payoutId) {
        Hold hold = findHold(payoutId);
        if (hold == null) {
            return SettleResult.NOTHING_HELD;
        }
        AccountBalance account = lockAccount(hold.accountId());
        String finalEntry = findFinalEntryType(payoutId);
        if ("SETTLE".equals(finalEntry)) {
            return SettleResult.ALREADY_SETTLED;
        }
        if ("RELEASE".equals(finalEntry)) {
            return SettleResult.RELEASED_CONFLICT;
        }
        apply(account, payoutId, "SETTLE", 0, -hold.amount(), "SETTLE:" + payoutId);
        return SettleResult.SETTLED;
    }

    /** Sets an account's available balance, creating the account if needed (development seeding). */
    @Transactional
    public void seedBalance(String accountId, long amount) {
        seedBalance(accountId, amount, "system");
    }

    /** Development seeding with the acting principal recorded in the audit log. */
    @Transactional
    public void seedBalance(String accountId, long amount, String actor) {
        createAccountIfAbsent(accountId);
        AccountBalance account = lockAccount(accountId);
        apply(account, null, "ADJUSTMENT", amount - account.available(), 0, "ADJUSTMENT:" + UUID.randomUUID());
        auditService.record(actor, "LEDGER_BALANCE_SEEDED", "ACCOUNT", accountId,
                AuditService.details("previousAvailable", account.available(), "newAvailable", amount));
        log.info("Set available balance of account {} to {}", accountId, amount);
    }

    /**
     * Adds funds to an account's available balance, creating the account when {@code createIfMissing}.
     * Idempotent per {@code idempotencyKey}: a repeated call returns the balance without crediting again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountBalance credit(String accountId, long amount, String idempotencyKey, boolean createIfMissing) {
        requirePositive(amount);
        if (createIfMissing) {
            createAccountIfAbsent(accountId);
        }
        AccountBalance account = lockAccount(accountId);
        if (account == null) {
            throw new AccountNotFoundException("Ledger account not found: " + accountId);
        }
        if (entryExists(idempotencyKey)) {
            return account;
        }
        return apply(account, null, "CREDIT", amount, 0, idempotencyKey);
    }

    /**
     * Removes funds from an account's available balance; held funds can never be debited and the
     * balance never goes negative. Idempotent per {@code idempotencyKey}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountBalance debit(String accountId, long amount, String idempotencyKey) {
        requirePositive(amount);
        AccountBalance account = lockAccount(accountId);
        if (account == null) {
            throw new AccountNotFoundException("Ledger account not found: " + accountId);
        }
        if (entryExists(idempotencyKey)) {
            return account;
        }
        if (account.available() < amount) {
            throw new InsufficientBalanceException("Insufficient available balance on account " + accountId
                    + " for a debit of " + amount);
        }
        return apply(account, null, "DEBIT", -amount, 0, idempotencyKey);
    }

    /**
     * Creates an account with an opening balance recorded under {@code idempotencyKey}. Returns false,
     * changing nothing, if the account already exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean openAccount(String accountId, long openingBalance, String idempotencyKey) {
        if (!createAccountIfAbsent(accountId)) {
            return false;
        }
        apply(lockAccount(accountId), null, "OPENING_BALANCE", openingBalance, 0, idempotencyKey);
        return true;
    }

    public boolean entryExists(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    public Long getBalance(String accountId) {
        AccountBalance account = getAccount(accountId);
        return account != null ? account.available() : null;
    }

    public AccountBalance getAccount(String accountId) {
        List<AccountBalance> rows = jdbc.query(
                "SELECT account_id, available_balance, held_balance FROM ledger_accounts WHERE account_id = ?",
                (rs, i) -> new AccountBalance(rs.getString(1), rs.getLong(2), rs.getLong(3)), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<LedgerEntry> recentEntries(String accountId, int limit) {
        return jdbc.query("""
                SELECT id, account_id, payout_id, entry_type, available_delta, held_delta, available_after,
                       held_after, idempotency_key, created_at
                  FROM ledger_entries WHERE account_id = ? ORDER BY id DESC LIMIT ?""",
                (rs, i) -> new LedgerEntry(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getString(9), rs.getObject(10, OffsetDateTime.class).toInstant()),
                accountId, limit);
    }

    private boolean createAccountIfAbsent(String accountId) {
        OffsetDateTime now = now();
        return jdbc.update("""
                INSERT INTO ledger_accounts (account_id, available_balance, held_balance, created_at, updated_at)
                VALUES (?, 0, 0, ?, ?) ON CONFLICT (account_id) DO NOTHING""", accountId, now, now) == 1;
    }

    private AccountBalance lockAccount(String accountId) {
        List<AccountBalance> rows = jdbc.query("""
                SELECT account_id, available_balance, held_balance FROM ledger_accounts
                 WHERE account_id = ? FOR UPDATE""",
                (rs, i) -> new AccountBalance(rs.getString(1), rs.getLong(2), rs.getLong(3)), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Hold findHold(UUID payoutId) {
        List<Hold> rows = jdbc.query("""
                SELECT account_id, -available_delta FROM ledger_entries
                 WHERE payout_id = ? AND entry_type = 'HOLD'""",
                (rs, i) -> new Hold(rs.getString(1), rs.getLong(2)), payoutId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String findFinalEntryType(UUID payoutId) {
        List<String> rows = jdbc.queryForList("""
                SELECT entry_type FROM ledger_entries
                 WHERE payout_id = ? AND entry_type IN ('RELEASE', 'SETTLE')""", String.class, payoutId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Ledger amounts must be positive");
        }
    }

    private AccountBalance apply(AccountBalance account, UUID payoutId, String entryType, long availableDelta,
                                 long heldDelta, String idempotencyKey) {
        long available = Math.addExact(account.available(), availableDelta);
        long held = Math.addExact(account.held(), heldDelta);
        if (available < 0 || held < 0) {
            throw new IllegalStateException("Ledger " + entryType + " would make account " + account.accountId()
                    + " negative");
        }
        OffsetDateTime now = now();
        jdbc.update("UPDATE ledger_accounts SET available_balance = ?, held_balance = ?, updated_at = ? WHERE account_id = ?",
                available, held, now, account.accountId());
        jdbc.update("""
                INSERT INTO ledger_entries (account_id, payout_id, entry_type, available_delta, held_delta,
                                            available_after, held_after, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                account.accountId(), payoutId, entryType, availableDelta, heldDelta, available, held,
                idempotencyKey, now);
        log.info("Ledger {} on account {} (payout {}): available {} -> {}, held {} -> {}", entryType,
                account.accountId(), payoutId, account.available(), available, account.held(), held);
        return new AccountBalance(account.accountId(), available, held);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
