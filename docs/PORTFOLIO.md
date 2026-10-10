# PaySentinel: portfolio and interview notes

Everything here describes behaviour that the code implements and that automated tests or local runs
have verified. The payment provider is a **simulator**: no real payment gateway is integrated, no real
money moves, and nothing here claims production readiness or compliance certification.

## Resume entry

**PaySentinel: Reliable Payout Processing & Ledger Reconciliation** (Java 21, Spring Boot 3.5,
PostgreSQL 16, Redis 7, Docker). Personal project with a simulated payment provider.

- Built a payout service on the **transactional outbox pattern**: the payout row, the ledger hold and
  the outbox event commit in one PostgreSQL transaction; a relay publishes events to a Redis work queue
  that workers consume with at-least-once delivery and crash recovery.
- Implemented an **append-only ledger** (available/held balances, database-enforced non-negative
  balances, one hold and one settle-or-release per payout) with automated **reconciliation** of balances
  against the journal.
- Made every step **idempotent**: client idempotency keys (replay returns the original, a changed payload
  returns 409), a stable idempotency key for every call to a **simulated** payment provider, and a
  manual-review state so a payout with an unknown provider outcome is never refunded automatically.
- Added API-key authentication with per-account authorization, Redis-based rate limiting, dual-approval
  funding adjustments with an audit log, and a startup check that refuses development-only settings
  outside a declared local environment.
- **146 automated tests** in the default build (JUnit 5, Testcontainers with real PostgreSQL and
  Redis, deterministic provider stubs) covering concurrency, crashes, retries, duplicate delivery,
  authorization and shutdown while a scheduled job is running; Flyway migrations, Docker Compose, and a
  GitHub Actions CI pipeline. CI actions are pinned to commit SHAs and the Docker base and Compose images
  to digests; the Testcontainers images used by the tests are not pinned.

## 60-second explanation

"PaySentinel is a payout service where correctness of money movement is the main concern. When a client requests
a payout, I write three things in one database transaction: the payout, a hold that moves the amount
from the account's available balance to its held balance, and an outbox event. Either all three exist
or none do, so I never hold money for a payout that won't be processed or queue a payout without funds.
A relay publishes outbox events to a Redis queue, and workers call a simulated payment provider. The
queue is at-least-once, so every step is idempotent: workers claim a payout with a conditional update
and a lease, every provider call for a payout carries the same idempotency key, and the final status
and the ledger settlement or refund commit together, at most once, enforced by unique indexes. If the
provider's answer is unknown, the payout goes to manual review with the funds still held instead of
being refunded. A reconciliation job re-derives every balance from the append-only journal. It's
covered by 146 tests against real PostgreSQL and Redis in containers."

## Likely interview questions

**1. How does idempotency work, and what happens with concurrent duplicate requests?**
Every payout request carries an idempotency key, which is unique per API client
(`UNIQUE (client_id, idempotency_key)`). A retry with the same key and the same payload returns the
original payout; the same key with a different payload returns 409. For concurrent duplicates, both
requests try to insert in their own transaction; the unique constraint lets exactly one commit, and the
other catches the violation and returns the winner's payout. A test fires eight identical requests in
parallel and asserts one payout and one hold. The same idea applies downstream: ledger entries have
unique idempotency keys (`HOLD:<payoutId>`, …), and provider calls use `payout-<payoutId>`.

**2. Why a transactional outbox instead of publishing to Redis directly?**
Because PostgreSQL and Redis can't share a transaction. If I committed the payout and then published,
a crash in between would lose the message; if I published first, a rollback would leave a message for
a payout that doesn't exist. With an outbox, the event is just another row in the same transaction as
the payout and the hold. A separate relay reads pending events (`FOR UPDATE SKIP LOCKED`, bounded
batches), publishes them, and marks them relayed after publishing. If it crashes between publishing and
marking, the event is published again, so delivery is at-least-once and consumers must be idempotent,
which is why the worker de-duplicates with a conditional claim.

**3. How do holds, settlement and refunds keep the balance correct?**
Each account has an available and a held balance. Accepting a payout moves the amount from available to
held. A completed payout settles the hold (held decreases, the money has left); a failed payout releases
it (back to available). Each movement locks the account row, writes an append-only journal entry, and
commits in the same transaction as the payout's status change. Database constraints keep balances
non-negative and allow one hold and one terminal entry (settle **or** release) per payout, so a completed
payout can never also be refunded. To be precise: it is a single-entry ledger with available/held
balances and a journal, not full double-entry bookkeeping with counter-accounts; double-entry is on the
roadmap.

**4. How are retries handled without paying twice?**
Provider timeouts are retried with exponential backoff (Spring Retry), always with the same
payout-specific idempotency key, so the provider returns the original result rather than paying again;
the simulator reproduces this, including a "paid but the response was lost" case. Workers hold a lease
on the payout and write the outcome only if their claim is still current (the attempt number acts as a
fencing token), so a worker that crashed and was replaced can't overwrite the result. Messages left by
a crashed worker are re-queued after a visibility timeout. After the last retry the worker asks the
provider: a definitive result is used, a confirmed "never executed" is refunded, and anything uncertain
goes to `MANUAL_REVIEW` with the funds held.

**5. What does reconciliation check, and what happens if it finds a problem?**
It verifies that every account's balances equal the sum of its journal entries, that each held balance
equals its open holds, and that every payout's funds status matches its ledger entries (for example, a
`SETTLED` payout must have a settle entry). It runs on a schedule and on demand through an admin
endpoint, and exports a mismatch gauge. A test tampers with a balance directly and checks that
reconciliation reports the drift. It detects problems and alerts; it does not repair them
automatically, because silently "fixing" money is worse than surfacing it for review.

## Locally verified demo (2026-10-10)

Run against the local Docker Compose stack (simulated provider):

| Step | Result |
|---|---|
| Seed `user_123` with 100,000 paise (₹1,000.00) via the dev-only seeding endpoint | HTTP 200 |
| Payout of 5,000 paise (₹50.00) | HTTP 202, `PENDING` / funds `HELD` |
| Identical replay with the same idempotency key | HTTP 202, same payout id, no second hold |
| Same key with a different amount | HTTP 409 `idempotency-key-reused`, nothing created |
| Polling the payout | `COMPLETED` / `SETTLED` on the first attempt, one provider call |
| Balance afterwards | available 95,000 paise (₹950.00), held 0 |
| Reconciliation | consistent, 0 mismatches |
| Fractional amount `50.75` | HTTP 400, no records created |

An upgrade rehearsal on throwaway containers also took the schema from the original Redis-ledger
version through V4 to V5, including hard-killed workers with payouts in flight, and ended with the
balance equal to the seed minus all completed payouts and a consistent reconciliation.

## Honest limitations

- The payment provider is simulated; a real integration would need the provider's idempotency and
  status-lookup guarantees confirmed.
- Single currency per account; amounts are paise by convention, not by a typed money model.
- API keys are static configuration (no rotation or expiry); no TLS termination in this setup.
- Published performance numbers predate the PostgreSQL ledger and have not been re-measured.
