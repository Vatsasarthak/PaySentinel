package com.pay.outbox.service;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.entity.OutboxEvent;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.OutboxStatus;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.repository.OutboxEventRepository;
import com.pay.outbox.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Repairs work left unfinished by crashes or infrastructure failures. Every step is idempotent and
 * safe to run concurrently with normal processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutRecoveryService {

    private final PayoutRepository payoutRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PayoutQueue payoutQueue;
    private final LedgerService ledgerService;
    private final PayoutMetrics payoutMetrics;
    private final PayoutProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final PayoutReviewService reviewService;
    private final Clock clock;

    /** Re-queues messages left in the processing list by a worker that died. */
    public int recoverAbandonedMessages(Instant now) {
        int requeued = 0;
        for (String message : new LinkedHashSet<>(payoutQueue.processingSnapshot(
                properties.getQueue().getRecoveryScanLimit()))) {
            if (payoutQueue.requeueIfStale(message, now, properties.getQueue().getVisibilityTimeout())) {
                requeued++;
            }
        }
        if (requeued > 0) {
            log.warn("Re-queued {} abandoned queue messages", requeued);
            payoutMetrics.incrementRequeued(requeued);
        }
        return requeued;
    }

    /**
     * Completes refunds still owed by payouts imported from the Redis ledger (funds status
     * RELEASE_PENDING). Each release and its status change commit together and apply at most once.
     */
    public int completePendingReleases() {
        List<Payout> owed = payoutRepository.findByFundsStatusIn(List.of(FundsStatus.RELEASE_PENDING), page());
        int released = 0;
        for (Payout payout : owed) {
            Boolean done = transactionTemplate.execute(status -> {
                if (payoutRepository.updateFundsStatus(payout.getId(), FundsStatus.RELEASE_PENDING,
                        FundsStatus.RELEASED, clock.instant()) == 0) {
                    return false;
                }
                LedgerService.ReleaseResult result = ledgerService.release(payout.getId());
                if (result == LedgerService.ReleaseResult.SETTLED_CONFLICT) {
                    throw new IllegalStateException("Payout " + payout.getId() + " is owed a refund but was settled");
                }
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                payoutMetrics.incrementRefunded();
                released++;
            }
        }
        if (released > 0) {
            log.info("Completed {} pending refunds", released);
        }
        return released;
    }

    /**
     * Re-publishes payouts that have been idle too long and whose queue message no longer exists
     * (for example after Redis lost data). Duplicates are harmless because the worker de-duplicates.
     */
    public int rearmStuckPayouts(Instant now) {
        Instant cutoff = now.minus(properties.getRecovery().getStuckPayoutTimeout());
        int rearmed = 0;
        for (Payout payout : payoutRepository.findStuck(cutoff, now, page())) {
            OutboxEvent event = outboxEventRepository.findFirstByPayoutId(payout.getId()).orElse(null);
            if (event == null) {
                log.error("Payout {} is {} but has no outbox event; manual review required",
                        payout.getId(), payout.getStatus());
                continue;
            }
            boolean needsRepublish = event.getStatus() == OutboxStatus.RELAYED
                    && !payoutQueue.isInFlight(event.getPayload());
            transactionTemplate.executeWithoutResult(status -> {
                if (needsRepublish) {
                    outboxEventRepository.rearm(payout.getId());
                }
                payoutRepository.touch(payout.getId(), now);
            });
            if (needsRepublish) {
                log.warn("Payout {} was stuck in {} without a queue message; re-publishing it",
                        payout.getId(), payout.getStatus());
                payoutMetrics.incrementRecovered();
                rearmed++;
            }
        }
        return rearmed;
    }

    /** Resolves payouts in manual review for which the provider now has a definitive answer. */
    public int recheckManualReviews() {
        return reviewService.recheckWithProvider();
    }

    private PageRequest page() {
        return PageRequest.of(0, properties.getRecovery().getBatchSize());
    }
}
