# PaySentinel architecture

This page shows how the running application is put together: the security filter chain, the
controllers and services, the background jobs, and what each component stores in PostgreSQL and Redis.
The payment provider is a **simulator** (`SimulatedPaymentProvider`): no real money moves and no bank
or payment network is called.

## Components

```mermaid
flowchart LR
  client["API client<br/>CLIENT key"]
  operator["Operator<br/>ADMIN or TREASURY key"]

  subgraph app["PaySentinel (Spring Boot)"]
    subgraph security["Security filter chain"]
      direction TB
      auth["ApiKeyAuthenticationFilter<br/>SHA-256 key match, sets the role"]
      limit["RateLimitFilter<br/>per-key token buckets"]
      rules["Authorization rules<br/>SecurityConfig"]
      auth --> limit --> rules
    end

    subgraph web["Controllers"]
      direction TB
      payouts["PayoutController<br/>POST, GET /api/v1/payouts<br/>CLIENT or ADMIN, account grants"]
      ledger["LedgerAdminController<br/>/api/v1/payouts/ledger/**, /dlq<br/>ADMIN only"]
      seed["LedgerSeedController<br/>POST /ledger/seed<br/>ADMIN, dev profile only"]
      treasury["TreasuryController<br/>/api/v1/treasury/**<br/>TREASURY only"]
    end

    subgraph services["Services"]
      direction TB
      payoutSvc["PayoutService"]
      ledgerSvc["LedgerService"]
      reconSvc["LedgerReconciliationService"]
      adjustSvc["LedgerAdjustmentService<br/>dual approval"]
      reviewSvc["PayoutReviewService<br/>manual review"]
      auditSvc["AuditService"]
    end

    subgraph jobs["Background jobs"]
      direction TB
      relay["OutboxRelayScheduler<br/>every 1 s, ShedLock"]
      worker["PaymentProcessingWorker<br/>polls every 200 ms"]
      recovery["PayoutRecoveryScheduler<br/>recovery every 30 s,<br/>reconciliation every 5 min, ShedLock"]
    end

    queue["PayoutQueue<br/>claim, ack, dead-letter, requeue"]
    gateway["PaymentGatewayService<br/>retries with key payout-ID,<br/>lookup after timeouts"]
    simulator["SimulatedPaymentProvider<br/>simulator only"]
  end

  postgres[("PostgreSQL<br/>payouts, outbox_events,<br/>ledger_accounts, ledger_entries,<br/>ledger_adjustments, audit_events")]
  redis[("Redis<br/>payout:queue, payout:processing,<br/>payout:dlq, ratelimit:*,<br/>job-lock:*, gateway:sim:payment:*")]

  client -->|X-API-Key| auth
  operator -->|X-API-Key| auth
  limit -.->|token buckets| redis
  rules --> payouts
  rules --> ledger
  rules --> seed
  rules --> treasury

  payouts --> payoutSvc
  ledger --> ledgerSvc
  ledger --> reconSvc
  ledger --> auditSvc
  ledger --> queue
  seed --> ledgerSvc
  treasury --> adjustSvc
  treasury --> reviewSvc

  payoutSvc -->|"one transaction:<br/>payout + HOLD + outbox event"| postgres
  ledgerSvc --> postgres
  reconSvc -->|read-only checks| postgres
  adjustSvc --> postgres
  reviewSvc --> postgres
  reviewSvc --> gateway
  auditSvc --> postgres

  relay -->|"SELECT ... FOR UPDATE SKIP LOCKED"| postgres
  relay -->|RPUSH| queue
  queue --> redis
  worker -->|claim, ack, dead-letter| queue
  worker -->|"claim with lease;<br/>finish + SETTLE or RELEASE"| postgres
  worker --> gateway
  gateway --> simulator
  simulator -.->|idempotency store| redis
  recovery -->|re-queue abandoned messages| queue
  recovery -->|"owed refunds;<br/>re-arm outbox events of stuck payouts"| postgres
  recovery --> reviewSvc
  recovery --> reconSvc
  relay -.->|ShedLock| redis
  recovery -.->|ShedLock| redis
```

## Startup order

```mermaid
flowchart LR
  start["Application start"] --> check["ProductionSafetyCheck<br/>refuses unsafe settings<br/>before any migration"]
  check --> flyway["Flyway migrations<br/>V1 to V5"]
  flyway --> importer["RedisLedgerImporter<br/>one-time legacy Redis ledger import"]
  importer --> serve["HTTP server and<br/>scheduled jobs start"]
```

## How the parts fit together

| Component | Responsibility | Source |
| --- | --- | --- |
| Security filter chain | Authenticates `X-API-Key` by SHA-256 digest (401 for an unknown key), applies per-key rate limits, then enforces role rules: `/api/v1/payouts/ledger/**` and `/api/v1/payouts/dlq` need ADMIN, `/api/v1/treasury/**` needs TREASURY, `/api/v1/payouts/**` needs CLIENT or ADMIN | `security/ApiKeyAuthenticationFilter`, `security/RateLimitFilter`, `config/SecurityConfig` |
| `PayoutController` | Creates payouts only for accounts granted to the key (403 `account-access-denied` otherwise); reads return 404 for payouts on accounts the key is not granted | `controller/PayoutController` |
| `PayoutService` | Writes the payout, the ledger `HOLD` and the outbox event in one PostgreSQL transaction; handles idempotency keys per client | `service/PayoutService` |
| `OutboxRelayScheduler` | Publishes pending outbox events to `payout:queue` and marks them relayed (at-least-once) | `scheduler/OutboxRelayScheduler` |
| `PaymentProcessingWorker` | Claims messages (`LMOVE` to `payout:processing`), claims the payout with a lease and fencing token, calls the provider, and records the outcome with its `SETTLE` or `RELEASE` entry in one transaction | `worker/PaymentProcessingWorker` |
| `PaymentGatewayService` | Retries timeouts with the same idempotency key; after the last timeout it looks the key up; unknown outcomes are never refunded | `service/PaymentGatewayService` |
| `PayoutRecoveryScheduler` | Re-queues abandoned messages, completes owed refunds, re-checks `MANUAL_REVIEW` payouts with the provider, re-publishes stuck payouts (their outbox event is set back to pending for the relay), and runs reconciliation | `scheduler/PayoutRecoveryScheduler`, `service/PayoutRecoveryService` |
| `LedgerReconciliationService` | Read-only consistency report: balances against journal entries, held funds against open holds, payout funds status against ledger entries | `service/LedgerReconciliationService` |
| `TreasuryController` | Dual-approval funding adjustments and manual-review resolutions, both audited | `controller/TreasuryController`, `service/LedgerAdjustmentService`, `service/PayoutReviewService` |

The payout lifecycle (sequence diagram), the ledger model and the dead-letter rules are described in
the [README](../README.md#payout-lifecycle).
