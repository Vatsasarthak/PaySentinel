package com.pay.outbox.service;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.dto.ReviewResolutionRequest;
import com.pay.outbox.exception.InvalidRequestException;
import com.pay.outbox.exception.ResourceNotFoundException;
import com.pay.outbox.exception.StateConflictException;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.repository.PayoutRepository;
import com.pay.outbox.security.ApiPrincipal;
import com.pay.outbox.service.PaymentGatewayService.ProviderCheck;
import com.pay.outbox.service.PaymentGatewayService.ProviderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Payouts in MANUAL_REVIEW have an unknown provider outcome and their funds still held. They leave
 * review exactly once, in a transaction that also settles (COMPLETED) or releases (FAILED) the hold:
 * <ul>
 *   <li>automatically, when a later provider lookup gives a definitive answer;</li>
 *   <li>manually, by a TREASURY key granted the account, with a mandatory evidence note. A manual
 *       resolution that contradicts a definitive provider answer is refused.</li>
 * </ul>
 * A missing provider record ("not found") is never enough to refund a payout in review automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutReviewService {

    public static final String MANUAL_FAILURE_REASON = "MANUAL_REVIEW_FAILED";

    private final PayoutRepository payoutRepository;
    private final PaymentGatewayService paymentGatewayService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final PayoutMetrics payoutMetrics;
    private final TransactionTemplate transactionTemplate;
    private final PayoutProperties properties;
    private final Clock clock;

    /** Re-asks the provider about payouts in review; resolves those with a definitive answer. */
    public int recheckWithProvider() {
        int resolved = 0;
        for (Payout payout : payoutRepository.findByStatusOrderByUpdatedAt(PayoutStatus.MANUAL_REVIEW,
                PageRequest.of(0, properties.getRecovery().getBatchSize()))) {
            ProviderCheck check = paymentGatewayService.checkProvider(payout.getId());
            if (!check.state().definitive()) {
                continue;
            }
            boolean completed = check.state() == ProviderState.COMPLETED;
            if (resolve(payout.getId(), completed, check.providerReference(), "system",
                    AuditService.details("source", "provider-recheck", "providerState", check.state().name()))) {
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("Resolved {} payouts in manual review from provider results", resolved);
        }
        return resolved;
    }

    public List<Payout> openReviews(ApiPrincipal principal) {
        return payoutRepository.findByStatusOrderByUpdatedAt(PayoutStatus.MANUAL_REVIEW,
                        PageRequest.of(0, properties.getRecovery().getBatchSize())).stream()
                .filter(payout -> principal.mayUseAccount(payout.getRecipientId()))
                .toList();
    }

    public Payout resolveManually(ApiPrincipal principal, UUID payoutId, ReviewResolutionRequest request) {
        Payout payout = payoutRepository.findById(payoutId)
                .filter(p -> principal.mayUseAccount(p.getRecipientId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payout not found with id: " + payoutId));
        if (payout.getStatus() != PayoutStatus.MANUAL_REVIEW) {
            throw new StateConflictException("Payout " + payoutId + " is " + payout.getStatus() + ", not in manual review");
        }
        boolean completed = request.getResolution() == ReviewResolutionRequest.Resolution.COMPLETED;
        String reference = request.getProviderReference() == null || request.getProviderReference().isBlank()
                ? null : request.getProviderReference();
        if (completed && reference == null) {
            throw new InvalidRequestException("providerReference",
                    "The provider payment reference is required to resolve a payout as COMPLETED");
        }

        ProviderCheck check = paymentGatewayService.checkProvider(payoutId);
        if (check.state() == ProviderState.COMPLETED && !completed) {
            throw new StateConflictException("The provider reports payout " + payoutId
                    + " as paid; it cannot be refunded");
        }
        if (check.state() == ProviderState.FAILED && completed) {
            throw new StateConflictException("The provider reports payout " + payoutId + " as failed");
        }

        if (!resolve(payoutId, completed, reference, principal.name(), AuditService.details(
                "source", "manual", "resolution", request.getResolution(), "providerReference", reference,
                "note", request.getNote(), "providerStateAtResolution", check.state().name()))) {
            throw new StateConflictException("Payout " + payoutId + " was resolved concurrently");
        }
        return payoutRepository.findById(payoutId).orElseThrow();
    }

    private boolean resolve(UUID payoutId, boolean completed, String providerReference, String actor,
                            java.util.Map<String, Object> details) {
        Boolean done = transactionTemplate.execute(status -> {
            int updated = completed
                    ? payoutRepository.resolveReview(payoutId, PayoutStatus.COMPLETED, FundsStatus.SETTLED, null,
                    providerReference, clock.instant())
                    : payoutRepository.resolveReview(payoutId, PayoutStatus.FAILED, FundsStatus.RELEASED,
                    MANUAL_FAILURE_REASON, null, clock.instant());
            if (updated == 0) {
                return false;
            }
            boolean applied = completed
                    ? ledgerService.settle(payoutId) == LedgerService.SettleResult.SETTLED
                    : ledgerService.release(payoutId) == LedgerService.ReleaseResult.RELEASED;
            if (!applied) {
                throw new IllegalStateException("Ledger " + (completed ? "settle" : "release") + " for payout "
                        + payoutId + " did not apply; the review was left open");
            }
            auditService.record(actor, completed ? "PAYOUT_REVIEW_COMPLETED" : "PAYOUT_REVIEW_FAILED", "PAYOUT",
                    payoutId.toString(), details);
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            log.warn("Payout {} left manual review as {} (by {})", payoutId, completed ? "COMPLETED" : "FAILED", actor);
            if (completed) {
                payoutMetrics.incrementCompleted();
            } else {
                payoutMetrics.incrementFailed();
                payoutMetrics.incrementRefunded();
            }
            return true;
        }
        return false;
    }
}
