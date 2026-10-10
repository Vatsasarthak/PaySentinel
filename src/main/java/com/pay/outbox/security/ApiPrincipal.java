package com.pay.outbox.security;

import java.util.Set;

/**
 * The authenticated API caller. Access to money is decided per ledger account: a principal may only
 * act on the accounts it was explicitly granted (or on every account, if {@code allAccounts} was
 * explicitly configured for a treasury key).
 *
 * @param name            principal name; payouts, idempotency keys and audit entries are attributed to it
 * @param role            CLIENT, ADMIN or TREASURY
 * @param accounts        ledger accounts this principal may use
 * @param allAccounts     explicit, configured permission to use every account (treasury only)
 * @param readAllPayouts  explicit, configured permission to read every payout (admin support use)
 */
public record ApiPrincipal(String name, String role, Set<String> accounts, boolean allAccounts,
                           boolean readAllPayouts) {

    public ApiPrincipal {
        accounts = Set.copyOf(accounts);
    }

    public boolean mayUseAccount(String accountId) {
        return allAccounts || accounts.contains(accountId);
    }

    public boolean mayReadAccount(String accountId) {
        return readAllPayouts || mayUseAccount(accountId);
    }

    @Override
    public String toString() {
        return name;
    }
}
