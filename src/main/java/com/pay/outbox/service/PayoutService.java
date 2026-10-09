package com.pay.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.entity.OutboxEvent;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.OutboxStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.dto.PayoutMessage;
import com.pay.outbox.dto.PayoutRequest;
import com.pay.outbox.dto.PayoutResponse;
import com.pay.outbox.exception.IdempotencyConflictException;
import com.pay.outbox.exception.InsufficientBalanceException;
import com.pay.outbox.exception.InvalidRequestException;
import com.pay.outbox.exception.PayoutNotFoundException;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.repository.OutboxEventRepository;
import com.pay.outbox.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts payouts. The payout row, the ledger hold and the outbox event are written in one
 * PostgreSQL transaction, so either all of them exist or none do. Idempotency keys are scoped to the
 * API client; the unique (client_id, idempotency_key) constraint resolves concurrent duplicates.
 * Authorization (may this client use the account?) is checked by the caller before this service runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private record Created(Payout payout, boolean rejected) {
    }

    private final PayoutRepository payoutRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PayoutMetrics payoutMetrics;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final PayoutProperties properties;
    private final Clock clock;

    public PayoutResponse initiatePayout(String clientId, PayoutRequest request) {
        if (!properties.getSupportedCurrencies().contains(request.getCurrency())) {
            throw new InvalidRequestException("currency", "Currency " + request.getCurrency() + " is not supported");
        }

        Payout existing = payoutRepository.findByClientIdAndIdempotencyKey(clientId, request.getIdempotencyKey())
                .orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }

        Created created;
        try {
            created = transactionTemplate.execute(status -> create(clientId, request));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request from the same client with the same idempotency key committed first.
            Payout winner = payoutRepository.findByClientIdAndIdempotencyKey(clientId, request.getIdempotencyKey())
                    .orElseThrow(() -> e);
            return replay(winner, request);
        }

        if (created.rejected()) {
            payoutMetrics.incrementRejected();
            throw insufficientBalance(request.getRecipientId());
        }
        payoutMetrics.incrementInitiated();
        log.info("Payout {} accepted for client {}; funds held and outbox event written",
                created.payout().getId(), clientId);
        return mapToResponse(created.payout());
    }

    @Transactional(readOnly = true)
    public PayoutResponse getPayoutById(UUID id) {
        Payout payout = payoutRepository.findById(id)
                .orElseThrow(() -> new PayoutNotFoundException("Payout not found with id: " + id));
        return mapToResponse(payout);
    }

    private Created create(String clientId, PayoutRequest request) {
        Instant now = clock.instant();
        Payout payout = payoutRepository.saveAndFlush(Payout.builder()
                .clientId(clientId)
                .recipientId(request.getRecipientId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .idempotencyKey(request.getIdempotencyKey())
                .status(PayoutStatus.PENDING)
                .fundsStatus(FundsStatus.NONE)
                .attempts(0)
                .createdAt(now)
                .updatedAt(now)
                .build());

        LedgerService.HoldResult hold = ledgerService.hold(payout.getRecipientId(), payout.getId(), payout.getAmount());
        if (hold != LedgerService.HoldResult.HELD) {
            // Recorded so that replaying the same request returns the same answer.
            payout.setStatus(PayoutStatus.REJECTED);
            return new Created(payoutRepository.saveAndFlush(payout), true);
        }

        payout.setFundsStatus(FundsStatus.HELD);
        payoutRepository.saveAndFlush(payout);
        outboxEventRepository.save(OutboxEvent.builder()
                .payoutId(payout.getId())
                .eventType("PAYOUT_INITIATED")
                .payload(buildPayload(payout))
                .status(OutboxStatus.PENDING)
                .createdAt(now)
                .build());
        return new Created(payout, false);
    }

    private PayoutResponse replay(Payout existing, PayoutRequest request) {
        boolean sameRequest = Objects.equals(existing.getRecipientId(), request.getRecipientId())
                && Objects.equals(existing.getAmount(), request.getAmount())
                && Objects.equals(existing.getCurrency(), request.getCurrency());
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different payout request", false);
        }
        log.info("Duplicate request detected for idempotency key: {}", request.getIdempotencyKey());
        return switch (existing.getStatus()) {
            case RESERVING -> throw new IdempotencyConflictException(
                    "A request with this idempotency key is still being processed; retry shortly", true);
            case REJECTED -> throw insufficientBalance(existing.getRecipientId());
            default -> mapToResponse(existing);
        };
    }

    private static InsufficientBalanceException insufficientBalance(String recipientId) {
        return new InsufficientBalanceException(
                "Insufficient balance or account not found for recipient: " + recipientId);
    }

    private PayoutResponse mapToResponse(Payout payout) {
        return PayoutResponse.builder()
                .id(payout.getId())
                .recipientId(payout.getRecipientId())
                .amount(payout.getAmount())
                .currency(payout.getCurrency())
                .status(payout.getStatus())
                .fundsStatus(payout.getFundsStatus())
                .failureReason(payout.getFailureReason())
                .providerReference(payout.getProviderReference())
                .createdAt(payout.getCreatedAt())
                .updatedAt(payout.getUpdatedAt())
                .attempts(payout.getAttempts())
                .build();
    }

    private String buildPayload(Payout payout) {
        try {
            return objectMapper.writeValueAsString(new PayoutMessage(
                    payout.getId(), payout.getRecipientId(), payout.getAmount(), payout.getCurrency()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build outbox event payload for payout " + payout.getId(), e);
        }
    }
}
