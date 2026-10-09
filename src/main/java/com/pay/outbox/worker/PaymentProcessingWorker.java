package com.pay.outbox.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.dto.PayoutMessage;
import com.pay.outbox.exception.PaymentExhaustedException;
import com.pay.outbox.exception.PaymentOutcomeUnknownException;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.repository.PayoutRepository;
import com.pay.outbox.service.LedgerService;
import com.pay.outbox.service.PaymentGatewayService;
import com.pay.outbox.service.gateway.ProviderResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Consumes payout messages. Safe under duplicate delivery and concurrent consumers on any number
 * of instances:
 * <ul>
 *   <li>A payout is claimed with a conditional UPDATE (PENDING, or PROCESSING with an expired lease).
 *       Losers of the race skip the message.</li>
 *   <li>The provider call runs outside any database transaction and always carries the payout's
 *       stable idempotency key.</li>
 *   <li>The outcome and its ledger effect (settle or release) commit in one transaction, and only if
 *       the claim is still ours (attempt number as fencing token).</li>
 *   <li>The Redis message is acknowledged only after that commit; until then a crash leaves it in the
 *       processing list for the recovery job to re-queue.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentProcessingWorker {

    static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    static final String MAX_DELIVERY_ATTEMPTS = "MAX_DELIVERY_ATTEMPTS";
    static final String DECLINED = "DECLINED";
    private static final Set<String> DEAD_LETTER_REASONS = Set.of(RETRIES_EXHAUSTED, MAX_DELIVERY_ATTEMPTS);

    private final PayoutQueue queue;
    private final PayoutRepository payoutRepository;
    private final PaymentGatewayService paymentGatewayService;
    private final LedgerService ledgerService;
    private final PayoutMetrics payoutMetrics;
    private final ObjectMapper objectMapper;
    private final PayoutProperties properties;
    private final Clock clock;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transactionTemplate;
    private final Semaphore slots;

    public PaymentProcessingWorker(PayoutQueue queue, PayoutRepository payoutRepository,
                                   PaymentGatewayService paymentGatewayService, LedgerService ledgerService,
                                   PayoutMetrics payoutMetrics, ObjectMapper objectMapper,
                                   PayoutProperties properties, Clock clock,
                                   @Qualifier("paymentWorkerExecutor") ThreadPoolTaskExecutor executor,
                                   TransactionTemplate transactionTemplate) {
        this.queue = queue;
        this.payoutRepository = payoutRepository;
        this.paymentGatewayService = paymentGatewayService;
        this.ledgerService = ledgerService;
        this.payoutMetrics = payoutMetrics;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.slots = new Semaphore(properties.getWorker().getConcurrency());
    }

    /**
     * Keeps every worker slot busy: claims messages only while a slot is free, so a slow payout
     * (provider retries with backoff) never delays the others, and no message sits claimed while
     * waiting for a thread.
     */
    @Scheduled(fixedDelayString = "${payout.worker.poll-interval-ms:200}")
    public void poll() {
        try {
            while (dispatch(false) > 0) {
                // keep claiming until the queue is empty or every worker slot is busy
            }
        } catch (RuntimeException e) {
            log.error("Polling the payout queue failed", e);
        }
    }

    /**
     * Dispatches up to one batch of messages (bounded by free worker slots) and waits for them to
     * finish. Returns the number of messages claimed. Used by tests to drive the worker deterministically.
     */
    public int drainOnce() {
        return dispatch(true);
    }

    private int dispatch(boolean awaitCompletion) {
        List<CompletableFuture<Void>> started = new ArrayList<>();
        while (started.size() < properties.getWorker().getBatchSize() && slots.tryAcquire()) {
            String message;
            try {
                message = queue.claimNext(clock.instant());
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (message == null) {
                slots.release();
                break;
            }
            started.add(CompletableFuture.runAsync(() -> processSafely(message), executor)
                    .whenComplete((ignored, error) -> slots.release()));
        }
        if (awaitCompletion) {
            CompletableFuture.allOf(started.toArray(CompletableFuture[]::new)).join();
        }
        return started.size();
    }

    private void processSafely(String message) {
        try {
            process(message);
        } catch (RuntimeException e) {
            // The message stays in the processing list and the payout keeps its lease; both expire
            // and the message is redelivered, bounded by max-delivery-attempts.
            log.error("Processing queue message failed; it will be redelivered after the visibility timeout", e);
        }
    }

    /** Processes one claimed message. Public so tests can simulate duplicate and concurrent delivery. */
    public void process(String rawMessage) {
        PayoutMessage message = parse(rawMessage);
        if (message == null) {
            return;
        }
        UUID payoutId = message.payoutId();

        if (payoutRepository.findById(payoutId).isEmpty()) {
            log.error("Payout {} referenced by a queue message does not exist; dead-lettering", payoutId);
            deadLetter(rawMessage, payoutId.toString());
            return;
        }

        Instant now = clock.instant();
        PayoutProperties.Worker worker = properties.getWorker();
        // The attempt number read under the claim's row lock is this worker's fencing token.
        Integer attempt = transactionTemplate.execute(status -> payoutRepository.claim(payoutId,
                now.plus(worker.getLeaseDuration()), now, worker.getMaxDeliveryAttempts()) == 1
                ? payoutRepository.findAttemptsById(payoutId)
                : null);
        if (attempt == null) {
            handleUnclaimable(rawMessage, payoutId, now);
            return;
        }

        PayoutStatus outcome;
        String providerReference = null;
        String reason = null;
        boolean deadLetter = false;
        try {
            ProviderResponse response = paymentGatewayService.processPayment(message);
            outcome = response.status();
            providerReference = response.providerReference();
            if (outcome == PayoutStatus.FAILED) {
                reason = DECLINED;
            }
        } catch (PaymentExhaustedException e) {
            outcome = PayoutStatus.FAILED;
            reason = RETRIES_EXHAUSTED;
            deadLetter = true;
        } catch (PaymentOutcomeUnknownException e) {
            // Leave the payout leased and the message unacknowledged: after the lease and visibility
            // timeout it is redelivered and resolved from the provider's final answer.
            log.warn("Outcome of payout {} is not known yet; it will be retried after the lease expires", payoutId);
            return;
        }

        if (!finish(payoutId, attempt, outcome, reason, providerReference)) {
            log.warn("Lease on payout {} (attempt {}) was lost before the outcome was recorded; "
                    + "the current owner decides the outcome", payoutId, attempt);
            queue.ack(rawMessage);
            return;
        }
        if (outcome == PayoutStatus.COMPLETED) {
            payoutMetrics.incrementCompleted();
        } else {
            payoutMetrics.incrementFailed();
            payoutMetrics.incrementRefunded();
        }
        log.info("Payout {} processed with status {} on attempt {}", payoutId, outcome, attempt);

        if (deadLetter) {
            deadLetter(rawMessage, payoutId.toString());
        } else {
            queue.ack(rawMessage);
        }
    }

    /** Records the outcome and settles or releases the hold in one transaction. */
    private boolean finish(UUID payoutId, int attempt, PayoutStatus outcome, String reason, String providerReference) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            FundsStatus funds = outcome == PayoutStatus.COMPLETED ? FundsStatus.SETTLED : FundsStatus.RELEASED;
            if (payoutRepository.finishProcessing(payoutId, attempt, outcome, funds, reason, providerReference,
                    clock.instant()) == 0) {
                return false;
            }
            if (outcome == PayoutStatus.COMPLETED) {
                requireLedger(ledgerService.settle(payoutId) == LedgerService.SettleResult.SETTLED,
                        "settle", payoutId);
            } else {
                requireLedger(ledgerService.release(payoutId) == LedgerService.ReleaseResult.RELEASED,
                        "release", payoutId);
            }
            return true;
        }));
    }

    private void handleUnclaimable(String rawMessage, UUID payoutId, Instant now) {
        boolean exhausted = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (payoutRepository.exhaustDeliveries(payoutId, MAX_DELIVERY_ATTEMPTS, now,
                    properties.getWorker().getMaxDeliveryAttempts()) == 0) {
                return false;
            }
            requireLedger(ledgerService.release(payoutId) == LedgerService.ReleaseResult.RELEASED,
                    "release", payoutId);
            return true;
        }));
        if (exhausted) {
            log.error("Payout {} exceeded {} delivery attempts; failed, refunded and dead-lettered", payoutId,
                    properties.getWorker().getMaxDeliveryAttempts());
            payoutMetrics.incrementFailed();
            payoutMetrics.incrementRefunded();
            deadLetter(rawMessage, payoutId.toString());
            return;
        }

        Payout current = payoutRepository.findById(payoutId).orElse(null);
        if (current != null && current.getStatus() == PayoutStatus.FAILED
                && DEAD_LETTER_REASONS.contains(current.getFailureReason())) {
            // Covers a crash between recording the failure and dead-lettering (deduplicated by payout ID).
            deadLetter(rawMessage, payoutId.toString());
            return;
        }

        log.info("Skipping duplicate delivery for payout {} in status {}", payoutId,
                current != null ? current.getStatus() : null);
        payoutMetrics.incrementDuplicateSkipped();
        queue.ack(rawMessage);
    }

    /** A payout in this state must have exactly one open hold; anything else rolls the transaction back. */
    private void requireLedger(boolean applied, String action, UUID payoutId) {
        if (!applied) {
            payoutMetrics.incrementLedgerInconsistency();
            throw new IllegalStateException("Ledger " + action + " for payout " + payoutId
                    + " did not apply; the payout state was left unchanged for investigation");
        }
    }

    private PayoutMessage parse(String rawMessage) {
        try {
            PayoutMessage message = objectMapper.readValue(rawMessage, PayoutMessage.class);
            if (message.payoutId() == null) {
                throw new IllegalArgumentException("payoutId is missing");
            }
            return message;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Malformed queue message ({}); dead-lettering it", e.getClass().getSimpleName());
            deadLetter(rawMessage, "malformed:" + Integer.toHexString(rawMessage.hashCode()));
            return null;
        }
    }

    private void deadLetter(String rawMessage, String dedupeId) {
        if (queue.deadLetter(rawMessage, dedupeId)) {
            payoutMetrics.incrementDeadLettered();
        }
    }
}
