package com.pay.outbox;

import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.service.gateway.GatewayOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Runs the real background jobs end to end; uses its own context that is closed afterwards. */
@TestPropertySource(properties = {
        "payout.scheduling.enabled=true",
        "payout.relay.interval-ms=200",
        "payout.worker.poll-interval-ms=200"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    void schedulingConfigRegistersAllJobsOnTheConfiguredScheduler() {
        assertThat(scheduledTaskHolder.getScheduledTasks()).hasSize(4); // relay, worker, recovery, reconciliation
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        assertThat(((ThreadPoolTaskScheduler) taskScheduler).getThreadNamePrefix()).isEqualTo("scheduler-");
    }

    @Test
    void payoutIsRelayedAndProcessedByTheBackgroundJobs() throws Exception {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        ledgerService.seedBalance(ACCOUNT, 10_000L);

        UUID id = createPayout("scheduling-key-01", 2_500L);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        Payout payout = payout(id);
        while (payout.getFundsStatus() != FundsStatus.SETTLED && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            payout = payout(id);
        }
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(payout.getFundsStatus()).isEqualTo(FundsStatus.SETTLED);
        assertThat(balance()).isEqualTo(7_500L);
    }
}
