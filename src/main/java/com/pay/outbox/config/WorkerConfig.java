package com.pay.outbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WorkerConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ThreadPoolTaskExecutor paymentWorkerExecutor(PayoutProperties properties) {
        PayoutProperties.Worker worker = properties.getWorker();
        // Abandoned messages are re-queued after the visibility timeout. It must outlive a worker's
        // lease, otherwise a message could be redelivered while its payout is still legitimately leased.
        if (properties.getQueue().getVisibilityTimeout().compareTo(worker.getLeaseDuration()) <= 0) {
            throw new IllegalStateException("payout.queue.visibility-timeout must be longer than payout.worker.lease-duration");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(worker.getConcurrency());
        executor.setMaxPoolSize(worker.getConcurrency());
        executor.setQueueCapacity(worker.getBatchSize());
        // Back-pressure instead of rejection: the polling thread processes the message itself.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("payment-worker-");
        // TODO(shutdown): with true, Spring waits for running payments only at bean destruction, after
        // the Redis connection factory has stopped (see SchedulingConfig). A payment finishing during
        // shutdown records its outcome in PostgreSQL, but its queue acknowledgement can fail with
        // "LettuceConnectionFactory is STOPPING"; the error is logged and the message stays in the
        // processing list. Recovery re-queues it after queue.visibility-timeout, and the next worker
        // cannot claim the finished payout, so it skips and acknowledges the duplicate: no second
        // provider call or ledger movement, only an error log and a delayed acknowledgement. If the
        // outcome could not be recorded either, the payout's lease expires and it is redelivered with
        // the same provider idempotency key, as after a crash. Setting false is not a drop-in fix: the
        // executor would stop accepting work when the context closes and CallerRunsPolicy silently
        // discards rejected tasks, so a message claimed during shutdown would wait for the visibility
        // timeout. Needs its own change and test.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
