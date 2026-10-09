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
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
