package com.pay.outbox.scheduler;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.entity.OutboxEvent;
import com.pay.outbox.domain.enums.OutboxStatus;
import com.pay.outbox.metrics.PayoutMetrics;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Publishes PENDING outbox events to the Redis queue in bounded batches.
 * <p>
 * An event is marked RELAYED only after its publish succeeded, and the status change commits after
 * the publish. If the commit fails, the event is published again on the next run, so delivery is
 * at-least-once and the worker de-duplicates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final PayoutQueue payoutQueue;
    private final TransactionTemplate transactionTemplate;
    private final PayoutProperties properties;
    private final PayoutMetrics payoutMetrics;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${payout.relay.interval-ms:1000}")
    @SchedulerLock(name = "outboxRelayScheduler", lockAtMostFor = "${payout.relay.lock-at-most-for:PT1M}")
    public void relay() {
        Integer relayed = transactionTemplate.execute(status -> relayBatch());
        if (relayed != null && relayed > 0) {
            payoutMetrics.incrementRelayed(relayed);
            log.info("Relayed {} outbox events", relayed);
        }
    }

    private int relayBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextPendingBatch(properties.getRelay().getBatchSize());
        if (batch.isEmpty()) {
            log.debug("No pending outbox events found");
            return 0;
        }

        int relayed = 0;
        for (OutboxEvent event : batch) {
            try {
                payoutQueue.publish(event.getPayload());
            } catch (RuntimeException e) {
                // Redis is most likely unavailable: stop here so the run stays well inside the lock
                // duration. This and the remaining events stay PENDING for the next run.
                log.error("Failed to publish outbox event {}; it will be retried", event.getId(), e);
                payoutMetrics.incrementRelayFailure();
                break;
            }
            event.setStatus(OutboxStatus.RELAYED);
            event.setProcessedAt(clock.instant());
            relayed++;
        }
        return relayed;
    }
}
