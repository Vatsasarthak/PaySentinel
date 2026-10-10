package com.pay.outbox.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Background jobs (outbox relay, queue worker, recovery). Disabled with
 * {@code payout.scheduling.enabled=false}, which the test profile uses so that tests drive the jobs
 * explicitly instead of racing them.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
@ConditionalOnProperty(name = "payout.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer {

    @Value("${payout.scheduling.pool-size:4}")
    private int poolSize;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduler-");
        // Must stay false. With false, the context-close event stops new runs without interrupting the
        // running ones, and the scheduler's lifecycle stop waits for running jobs before lower-phase
        // beans such as the Redis connection factory stop. With true, Spring skips that wait and only
        // waits during bean destruction, after Redis has stopped, so a relay still running at shutdown
        // could not release its ShedLock lock ("LockException: Can not remove node", caused by
        // "LettuceConnectionFactory is STOPPING"). SchedulerShutdownIntegrationTest covers this.
        //
        // Limitation: the lifecycle wait is bounded by spring.lifecycle.timeout-per-shutdown-phase
        // (30 seconds by default). A job still running after that is interrupted at bean destruction
        // and given up to awaitTerminationSeconds more. Nothing is lost: an interrupted relay leaves its
        // events PENDING (re-published on the next run, de-duplicated by the worker), an unreleased
        // ShedLock lock expires after lockAtMostFor, and a payout left mid-processing is redelivered
        // after its lease and the queue visibility timeout expire.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
