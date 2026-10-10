package com.pay.outbox.controller;

import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.dto.AdjustmentRequest;
import com.pay.outbox.dto.DecisionRequest;
import com.pay.outbox.dto.ReviewResolutionRequest;
import com.pay.outbox.security.ApiPrincipal;
import com.pay.outbox.service.LedgerAdjustmentService;
import com.pay.outbox.service.LedgerAdjustmentService.Adjustment;
import com.pay.outbox.service.PayoutReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Treasury operations (TREASURY role only, enforced by SecurityConfig): audited funding adjustments
 * with dual control, and resolution of payouts in manual review. Every call is limited to the
 * accounts granted to the treasury key; resources on other accounts are reported as not found.
 */
@RestController
@RequestMapping("/api/v1/treasury")
@RequiredArgsConstructor
public class TreasuryController {

    public record ReviewView(UUID id, String recipientId, long amount, String currency, String status,
                             String fundsStatus, String reason, String providerReference, int attempts,
                             Instant createdAt, Instant updatedAt) {
        static ReviewView of(Payout p) {
            return new ReviewView(p.getId(), p.getRecipientId(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                    p.getFundsStatus().name(), p.getFailureReason(), p.getProviderReference(), p.getAttempts(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    private final LedgerAdjustmentService adjustmentService;
    private final PayoutReviewService reviewService;

    @PostMapping("/adjustments")
    public ResponseEntity<Adjustment> requestAdjustment(@AuthenticationPrincipal ApiPrincipal principal,
                                                        @Valid @RequestBody AdjustmentRequest request) {
        LedgerAdjustmentService.Submitted submitted = adjustmentService.request(principal, request);
        return ResponseEntity.status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(submitted.adjustment());
    }

    @GetMapping("/adjustments/{id}")
    public Adjustment getAdjustment(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable UUID id) {
        return adjustmentService.get(principal, id);
    }

    @PostMapping("/adjustments/{id}/approve")
    public Adjustment approve(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable UUID id,
                              @Valid @RequestBody DecisionRequest decision) {
        return adjustmentService.approve(principal, id, decision.getNote());
    }

    @PostMapping("/adjustments/{id}/reject")
    public Adjustment reject(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable UUID id,
                             @Valid @RequestBody DecisionRequest decision) {
        return adjustmentService.reject(principal, id, decision.getNote());
    }

    @GetMapping("/payout-reviews")
    public List<ReviewView> openReviews(@AuthenticationPrincipal ApiPrincipal principal) {
        return reviewService.openReviews(principal).stream().map(ReviewView::of).toList();
    }

    @PostMapping("/payout-reviews/{id}/resolution")
    public ReviewView resolve(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable UUID id,
                              @Valid @RequestBody ReviewResolutionRequest request) {
        return ReviewView.of(reviewService.resolveManually(principal, id, request));
    }
}
