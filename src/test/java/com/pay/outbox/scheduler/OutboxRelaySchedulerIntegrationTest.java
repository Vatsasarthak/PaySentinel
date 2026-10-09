package com.pay.outbox.scheduler;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.domain.enums.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class OutboxRelaySchedulerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PayoutProperties properties;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, 1_000_000L);
    }

    @Test
    void publishesPendingEventsAndMarksThemRelayed() {
        UUID first = createPayout("relay-key-0001", 100L);
        UUID second = createPayout("relay-key-0002", 200L);

        relay.relay();

        assertThat(payoutQueue.queuedCount()).isEqualTo(2);
        assertThat(redisTemplate.opsForList().range("payout:queue", 0, -1))
                .containsExactly(payloadOf(first), payloadOf(second));
        assertThat(outboxEventRepository.findAll())
                .allSatisfy(event -> {
                    assertThat(event.getStatus()).isEqualTo(OutboxStatus.RELAYED);
                    assertThat(event.getProcessedAt()).isNotNull();
                });
    }

    @Test
    void doesNotPublishRelayedEventsAgain() {
        createPayout("relay-key-0003", 100L);

        relay.relay();
        relay.relay();

        assertThat(payoutQueue.queuedCount()).isEqualTo(1);
    }

    @Test
    void leavesEventsPendingWhenPublishingFailsAndRetriesOnTheNextRun() {
        createPayout("relay-key-0004", 100L);
        createPayout("relay-key-0005", 200L);
        doThrow(new RedisConnectionFailureException("simulated outage"))
                .doCallRealMethod()
                .when(payoutQueue).publish(anyString());

        relay.relay();

        assertThat(payoutQueue.queuedCount()).isZero();
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).hasSize(2);

        relay.relay();

        assertThat(payoutQueue.queuedCount()).isEqualTo(2);
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.RELAYED)).hasSize(2);
    }

    @Test
    void relaysInBoundedBatches() {
        createPayout("relay-key-0006", 100L);
        createPayout("relay-key-0007", 100L);
        createPayout("relay-key-0008", 100L);
        int original = properties.getRelay().getBatchSize();
        properties.getRelay().setBatchSize(2);
        try {
            relay.relay();

            assertThat(payoutQueue.queuedCount()).isEqualTo(2);
            assertThat(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).hasSize(1);

            relay.relay();
            assertThat(payoutQueue.queuedCount()).isEqualTo(3);
        } finally {
            properties.getRelay().setBatchSize(original);
        }
    }
}
