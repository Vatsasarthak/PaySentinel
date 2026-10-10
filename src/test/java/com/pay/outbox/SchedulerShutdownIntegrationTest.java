package com.pay.outbox;

import com.pay.outbox.dto.PayoutRequest;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.service.LedgerService;
import com.pay.outbox.service.PayoutService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for an intermittent shutdown failure: the outbox relay was still running when the
 * Redis connection factory stopped, so ShedLock could not remove its lock ({@code LockException: Can
 * not remove node}, caused by {@code LettuceConnectionFactory is STOPPING}).
 * <p>
 * Each run starts its own application context with the real scheduler, holds the relay inside
 * {@code publish()} (so it owns the ShedLock lock and an open transaction), closes the context, and
 * checks that Redis stays up until the relay has finished, the event is published exactly once and
 * marked RELAYED, and the lock is released. Set {@code -Dpayout.shutdown-test.runs=N} to repeat it.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchedulerShutdownIntegrationTest {

    private static final String RELAY_LOCK_KEY = "job-lock:default:outboxRelayScheduler";
    /** How long the relay is held after shutdown began; Redis must not stop during this time. */
    private static final Duration HOLD_DURING_SHUTDOWN = Duration.ofSeconds(2);

    static IntStream runs() {
        return IntStream.rangeClosed(1, Integer.getInteger("payout.shutdown-test.runs", 3));
    }

    @ParameterizedTest(name = "shutdown run {0}")
    @MethodSource("runs")
    void contextCloseLetsAnActiveRelayFinishBeforeRedisStops(int run, CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();
        BlockingPayoutQueue.arm();
        ConfigurableApplicationContext context = start();
        Thread closer = null;
        try {
            context.getBean(JdbcTemplate.class).execute(
                    "TRUNCATE audit_events, ledger_adjustments, ledger_entries, ledger_accounts, outbox_events, payouts");
            @SuppressWarnings("unchecked")
            RedisTemplate<String, String> redisTemplate = context.getBean("redisTemplate", RedisTemplate.class);
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.serverCommands().flushDb();
                return null;
            });

            context.getBean(LedgerService.class).seedBalance("test_user", 10_000L);
            context.getBean(PayoutService.class).initiatePayout("test-client", request("shutdown-run-" + run));

            // The scheduled relay is now inside publish(): it holds the ShedLock lock and a transaction.
            assertThat(BlockingPayoutQueue.entered.await(15, TimeUnit.SECONDS)).as("relay started").isTrue();
            assertThat(redisTemplate.hasKey(RELAY_LOCK_KEY)).as("relay lock held").isTrue();

            LettuceConnectionFactory redisFactory = context.getBean(LettuceConnectionFactory.class);
            CountDownLatch closing = new CountDownLatch(1);
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    closing.countDown();
                }
            });
            closer = new Thread(context::close, "context-closer-" + run);
            closer.start();
            assertThat(closing.await(15, TimeUnit.SECONDS)).as("shutdown started").isTrue();

            // Shutdown must wait for the running relay instead of stopping Redis underneath it.
            Instant holdUntil = Instant.now().plus(HOLD_DURING_SHUTDOWN);
            while (Instant.now().isBefore(holdUntil) && redisFactory.isRunning()) {
                Thread.sleep(50);
            }
            assertThat(redisFactory.isRunning()).as("Redis still running while the relay is active").isTrue();
            assertThat(closer.isAlive()).as("shutdown waits for the relay").isTrue();
        } finally {
            BlockingPayoutQueue.release.countDown();
            if (closer != null) {
                closer.join(TimeUnit.SECONDS.toMillis(60));
            }
            context.close();
        }

        assertThat(closer.isAlive()).as("shutdown completed").isFalse();
        String shutdownLog = output.getAll().substring(logStart);
        assertThat(shutdownLog).doesNotContain("LockException", "Can not remove node", "is STOPPING");
        assertThat(redisCli("EXISTS", RELAY_LOCK_KEY)).as("relay lock released").isEqualTo("0");
        assertThat(redisCli("LLEN", PayoutQueue.QUEUE_KEY)).as("message published exactly once").isEqualTo("1");
        assertThat(outboxStatuses()).as("event marked relayed").containsExactly("RELAYED");
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(OutboxApplication.class, BlockingPublishConfig.class)
                .profiles("test")
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("excludeTestConfigurations", new ExcludeTestConfigurations()))
                .run("--spring.datasource.url=" + BaseIntegrationTest.postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + BaseIntegrationTest.postgres.getUsername(),
                        "--spring.datasource.password=" + BaseIntegrationTest.postgres.getPassword(),
                        "--spring.data.redis.host=" + BaseIntegrationTest.redis.getHost(),
                        "--spring.data.redis.port=" + BaseIntegrationTest.redis.getMappedPort(6379),
                        "--spring.data.redis.password=",
                        "--server.port=0",
                        "--payout.scheduling.enabled=true",
                        "--payout.relay.interval-ms=100",
                        // Only the relay should touch the queue: the worker polls once at startup
                        // (empty queue) and recovery never runs during the test.
                        "--payout.worker.poll-interval-ms=3600000",
                        "--payout.recovery.initial-delay-ms=3600000");
    }

    private static PayoutRequest request(String idempotencyKey) {
        PayoutRequest request = new PayoutRequest();
        request.setRecipientId("test_user");
        request.setAmount(2_500L);
        request.setCurrency("INR");
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private static String redisCli(String... command) throws Exception {
        String[] args = new String[command.length + 1];
        args[0] = "redis-cli";
        System.arraycopy(command, 0, args, 1, command.length);
        return BaseIntegrationTest.redis.execInContainer(args).getStdout().trim();
    }

    private static List<String> outboxStatuses() throws Exception {
        List<String> statuses = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(BaseIntegrationTest.postgres.getJdbcUrl(),
                BaseIntegrationTest.postgres.getUsername(), BaseIntegrationTest.postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select status from outbox_events")) {
            while (rows.next()) {
                statuses.add(rows.getString(1));
            }
        }
        return statuses;
    }

    /** Holds the first publish until the test releases it, then publishes for real. */
    static class BlockingPayoutQueue extends PayoutQueue {

        static volatile CountDownLatch entered;
        static volatile CountDownLatch release;

        BlockingPayoutQueue(RedisTemplate<String, String> redisTemplate) {
            super(redisTemplate);
        }

        static void arm() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public void publish(String message) {
            entered.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("publish was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("relay interrupted during shutdown", e);
            }
            super.publish(message);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingPublishConfig {
        @Bean
        @Primary
        PayoutQueue blockingPayoutQueue(RedisTemplate<String, String> redisTemplate) {
            return new BlockingPayoutQueue(redisTemplate);
        }
    }

    /**
     * Keeps component scanning of this hand-built context from picking up test configurations (as
     * {@code @SpringBootTest} does); {@link BlockingPublishConfig} is registered explicitly.
     */
    static final class ExcludeTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().isAnnotated(TestConfiguration.class.getName());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestConfigurations;
        }

        @Override
        public int hashCode() {
            return ExcludeTestConfigurations.class.hashCode();
        }
    }
}
