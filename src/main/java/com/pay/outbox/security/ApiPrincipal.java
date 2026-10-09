package com.pay.outbox.security;

import java.util.Set;

/**
 * The authenticated API client. Access to payouts is decided per ledger account: a principal may
 * only create payouts for, and read payouts of, the accounts it was explicitly granted.
 *
 * @param name            client name; payouts and their idempotency keys are scoped to it
 * @param role            CLIENT or ADMIN
 * @param accounts        ledger accounts this principal may use
 * @param readAllPayouts  explicit, configured permission to read every payout (admin support use)
 */
public record ApiPrincipal(String name, String role, Set<String> accounts, boolean readAllPayouts) {

    public ApiPrincipal {
        accounts = Set.copyOf(accounts);
    }

    public boolean mayUseAccount(String accountId) {
        return accounts.contains(accountId);
    }

    public boolean mayReadAccount(String accountId) {
        return readAllPayouts || accounts.contains(accountId);
    }

    @Override
    public String toString() {
        return name;
    }
}
