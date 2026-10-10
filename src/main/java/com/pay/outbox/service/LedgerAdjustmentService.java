package com.pay.outbox.service;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.dto.AdjustmentRequest;
import com.pay.outbox.exception.AccountAccessDeniedException;
import com.pay.outbox.exception.FourEyesViolationException;
import com.pay.outbox.exception.IdempotencyConflictException;
import com.pay.outbox.exception.InvalidRequestException;
import com.pay.outbox.exception.ResourceNotFoundException;
import com.pay.outbox.exception.StateConflictException;
import com.pay.outbox.security.ApiPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production funding: credits and debits of an account's available balance by treasury keys.
 * <ul>
 *   <li>Authorization: only TREASURY principals, and only for accounts they are granted.</li>
 *   <li>Dual control (default): a request is posted only when a different treasury principal approves
 *       it; the database refuses a posted adjustment approved by its own requester.</li>
 *   <li>Idempotency: (requester, idempotency key) is unique; an identical retry returns the original,
 *       a different payload is a 409. Posting uses ledger idempotency key {@code ADJUSTMENT:<id>}.</li>
 *   <li>Immutability and audit: posting appends CREDIT/DEBIT ledger entries and audit events, both
 *       append-only at the database level. Held funds can never be debited and balances never go negative.</li>
 * </ul>
 * Approving and posting happen in one transaction with the adjustment row locked, so concurrent
 * approvals post at most once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerAdjustmentService {

    public record Adjustment(UUID id, String requestedBy, String idempotencyKey, String accountId, String direction,
                             long amount, String currency, String reasonCode, String note, String externalReference,
                             boolean approvalRequired, String status, String decidedBy, Instant decidedAt,
                             String decisionNote, Instant createdAt) {
    }

    public record Submitted(Adjustment adjustment, boolean created) {
    }

    private static final String COLUMNS = """
            id, requested_by, idempotency_key, account_id, direction, amount, currency, reason_code, note,
            external_reference, approval_required, status, decided_by, decided_at, decision_note, created_at""";

    private static final RowMapper<Adjustment> MAPPER = (rs, i) -> new Adjustment(
            rs.getObject("id", UUID.class), rs.getString("requested_by"), rs.getString("idempotency_key"),
            rs.getString("account_id"), rs.getString("direction"), rs.getLong("amount"), rs.getString("currency"),
            rs.getString("reason_code"), rs.getString("note"), rs.getString("external_reference"),
            rs.getBoolean("approval_required"), rs.getString("status"), rs.getString("decided_by"),
            toInstant(rs.getObject("decided_at", OffsetDateTime.class)), rs.getString("decision_note"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final PayoutProperties properties;
    private final Clock clock;

    public Submitted request(ApiPrincipal principal, AdjustmentRequest request) {
        if (!principal.mayUseAccount(request.getAccountId())) {
            throw new AccountAccessDeniedException("This API key is not authorized for account " + request.getAccountId());
        }
        if (!properties.getSupportedCurrencies().contains(request.getCurrency())) {
            throw new InvalidRequestException("currency", "Currency " + request.getCurrency() + " is not supported");
        }
        Optional<Adjustment> existing = findByRequest(principal.name(), request.getIdempotencyKey());
        if (existing.isPresent()) {
            return new Submitted(replay(existing.get(), request), false);
        }

        boolean approvalRequired = properties.getFunding().isRequireApproval();
        try {
            Adjustment created = transactionTemplate.execute(status -> {
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ledger_adjustments (id, requested_by, idempotency_key, account_id, direction, amount,
                               currency, reason_code, note, external_reference, approval_required, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?)""",
                        id, principal.name(), request.getIdempotencyKey(), request.getAccountId(),
                        request.getDirection().name(), request.getAmount(), request.getCurrency(),
                        request.getReasonCode().name(), request.getNote(), blankToNull(request.getExternalReference()),
                        approvalRequired, now());
                auditService.record(principal.name(), "ADJUSTMENT_REQUESTED", "ADJUSTMENT", id.toString(),
                        AuditService.details("accountId", request.getAccountId(), "direction", request.getDirection(),
                                "amount", request.getAmount(), "currency", request.getCurrency(),
                                "reasonCode", request.getReasonCode(), "note", request.getNote(),
                                "externalReference", request.getExternalReference(),
                                "approvalRequired", approvalRequired));
                Adjustment adjustment = lock(id);
                if (!approvalRequired) {
                    post(adjustment, principal.name(), "Posted without second approval (funding.require-approval=false)");
                }
                return load(id);
            });
            log.info("Adjustment {} requested by {}: {} {} on account {} ({})", created.id(), principal.name(),
                    created.direction(), created.amount(), created.accountId(), created.status());
            return new Submitted(created, true);
        } catch (DataIntegrityViolationException e) {
            // A concurrent identical request from the same principal committed first.
            Adjustment winner = findByRequest(principal.name(), request.getIdempotencyKey()).orElseThrow(() -> e);
            return new Submitted(replay(winner, request), false);
        }
    }

    public Adjustment approve(ApiPrincipal principal, UUID id, String note) {
        return transactionTemplate.execute(status -> {
            Adjustment adjustment = lockVisible(principal, id);
            requirePending(adjustment);
            if (adjustment.approvalRequired() && adjustment.requestedBy().equals(principal.name())) {
                throw new FourEyesViolationException("An adjustment must be approved by a different treasury key "
                        + "than the one that requested it");
            }
            post(adjustment, principal.name(), note);
            return load(id);
        });
    }

    public Adjustment reject(ApiPrincipal principal, UUID id, String note) {
        return transactionTemplate.execute(status -> {
            Adjustment adjustment = lockVisible(principal, id);
            requirePending(adjustment);
            jdbc.update("""
                    UPDATE ledger_adjustments SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ?
                     WHERE id = ? AND status = 'PENDING_APPROVAL'""", principal.name(), now(), note, id);
            auditService.record(principal.name(), "ADJUSTMENT_REJECTED", "ADJUSTMENT", id.toString(),
                    AuditService.details("note", note));
            log.info("Adjustment {} rejected by {}", id, principal.name());
            return load(id);
        });
    }

    public Adjustment get(ApiPrincipal principal, UUID id) {
        Adjustment adjustment = findById(id).orElseThrow(() -> notFound(id));
        if (!principal.mayUseAccount(adjustment.accountId())) {
            throw notFound(id);
        }
        return adjustment;
    }

    private void post(Adjustment adjustment, String decidedBy, String note) {
        String ledgerKey = "ADJUSTMENT:" + adjustment.id();
        LedgerService.AccountBalance balance = "CREDIT".equals(adjustment.direction())
                ? ledgerService.credit(adjustment.accountId(), adjustment.amount(), ledgerKey, true)
                : ledgerService.debit(adjustment.accountId(), adjustment.amount(), ledgerKey);
        int updated = jdbc.update("""
                UPDATE ledger_adjustments SET status = 'POSTED', decided_by = ?, decided_at = ?, decision_note = ?
                 WHERE id = ? AND status = 'PENDING_APPROVAL'""", decidedBy, now(), note, adjustment.id());
        if (updated != 1) {
            throw new StateConflictException("Adjustment " + adjustment.id() + " is no longer pending");
        }
        auditService.record(decidedBy, "ADJUSTMENT_POSTED", "ADJUSTMENT", adjustment.id().toString(),
                AuditService.details("accountId", adjustment.accountId(), "direction", adjustment.direction(),
                        "amount", adjustment.amount(), "note", note, "availableAfter", balance.available(),
                        "heldAfter", balance.held()));
        log.info("Adjustment {} posted by {}: {} {} on account {}", adjustment.id(), decidedBy,
                adjustment.direction(), adjustment.amount(), adjustment.accountId());
    }

    private Adjustment replay(Adjustment existing, AdjustmentRequest request) {
        boolean same = Objects.equals(existing.accountId(), request.getAccountId())
                && Objects.equals(existing.direction(), request.getDirection().name())
                && existing.amount() == request.getAmount()
                && Objects.equals(existing.currency(), request.getCurrency())
                && Objects.equals(existing.reasonCode(), request.getReasonCode().name());
        if (!same) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different adjustment request", false);
        }
        return existing;
    }

    private Adjustment lockVisible(ApiPrincipal principal, UUID id) {
        Adjustment adjustment = lock(id);
        if (adjustment == null || !principal.mayUseAccount(adjustment.accountId())) {
            throw notFound(id);
        }
        return adjustment;
    }

    private static void requirePending(Adjustment adjustment) {
        if (!"PENDING_APPROVAL".equals(adjustment.status())) {
            throw new StateConflictException("Adjustment " + adjustment.id() + " is already " + adjustment.status());
        }
    }

    private Adjustment lock(UUID id) {
        List<Adjustment> rows = jdbc.query("SELECT " + COLUMNS + " FROM ledger_adjustments WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Adjustment load(UUID id) {
        return findById(id).orElseThrow(() -> notFound(id));
    }

    private Optional<Adjustment> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_adjustments WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    private Optional<Adjustment> findByRequest(String requestedBy, String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_adjustments WHERE requested_by = ? AND idempotency_key = ?",
                MAPPER, requestedBy, idempotencyKey).stream().findFirst();
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Adjustment not found: " + id);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
