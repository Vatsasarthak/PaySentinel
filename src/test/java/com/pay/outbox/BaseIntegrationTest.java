package com.pay.outbox;

import com.pay.outbox.domain.entity.Payout;
import com.pay.outbox.dto.PayoutRequest;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.repository.OutboxEventRepository;
import com.pay.outbox.repository.PayoutRepository;
import com.pay.outbox.security.RateLimiter;
import com.pay.outbox.service.LedgerService;
import com.pay.outbox.service.PayoutService;
import com.pay.outbox.service.gateway.GatewayOutcomeSource;
import com.pay.outbox.service.gateway.SimulatedPaymentProvider;
import com.pay.outbox.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Shared Spring context for integration tests against real PostgreSQL and Redis containers.
 * Background scheduling is disabled (test profile) so tests drive the relay, worker and recovery
 * jobs explicitly; the gateway outcome source is a mock so outcomes are deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BaseIntegrationTest.TestClockConfig.class)
public abstract class BaseIntegrationTest {

    protected static final String ACCOUNT = "test_user";
    protected static final String OTHER_ACCOUNT = "other_user";
    protected static final String SHARED_ACCOUNT = "shared_account";
    protected static final String CLIENT_ID = "test-client";
    protected static final String OTHER_CLIENT_ID = "other-client";
    protected static final String CLIENT_KEY = "test-client-api-key-0123456789abcdef";
    protected static final String OTHER_CLIENT_KEY = "other-client-api-key-0123456789abcdef";
    protected static final String ADMIN_KEY = "test-admin-api-key-0123456789abcdef";
    protected static final String TREASURY_A_KEY = "test-treasury-a-key-0123456789abcdef";
    protected static final String TREASURY_B_KEY = "test-treasury-b-key-0123456789abcdef";

    // Singleton containers: started once per JVM and shared by every test class.
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payment_db_test")
            .withUsername("payment_user")
            .withPassword("payment_pass");

    protected static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        OutboxApplication.useUtcAsDefaultTimeZone();
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @MockitoBean
    protected GatewayOutcomeSource gatewayOutcomes;

    @MockitoSpyBean
    protected PayoutQueue payoutQueue;

    @MockitoSpyBean
    protected RateLimiter rateLimiter;

    @MockitoSpyBean
    protected SimulatedPaymentProvider simulatedProvider;

    @Autowired
    protected LedgerService ledgerService;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected PayoutService payoutService;

    @Autowired
    protected PayoutRepository payoutRepository;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected RedisTemplate<String, String> redisTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Runs before and after every test so that no state leaks into the next test class (a class with
     * live background jobs would otherwise pick up messages left behind by the previous one).
     */
    @BeforeEach
    @AfterEach
    void resetState() {
        clock.reset();
        jdbcTemplate.execute(
                "TRUNCATE audit_events, ledger_adjustments, ledger_entries, ledger_accounts, outbox_events, payouts");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    protected static PayoutRequest request(String idempotencyKey, long amount) {
        PayoutRequest request = new PayoutRequest();
        request.setRecipientId(ACCOUNT);
        request.setAmount(amount);
        request.setCurrency("INR");
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    protected UUID createPayout(String idempotencyKey, long amount) {
        return payoutService.initiatePayout(CLIENT_ID, request(idempotencyKey, amount)).getId();
    }

    protected Payout payout(UUID id) {
        return payoutRepository.findById(id).orElseThrow();
    }

    protected String payloadOf(UUID payoutId) {
        return outboxEventRepository.findFirstByPayoutId(payoutId).orElseThrow().getPayload();
    }

    protected long balance() {
        return ledgerService.getBalance(ACCOUNT);
    }

    protected long held() {
        return ledgerService.getAccount(ACCOUNT).held();
    }

    protected int auditEvents(String targetId, String action) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_events where target_id = ? and action = ?", Integer.class, targetId, action);
        return count != null ? count : 0;
    }

    protected int ledgerEntries(UUID payoutId, String entryType) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entries where payout_id = ? and entry_type = ?", Integer.class,
                payoutId, entryType);
        return count != null ? count : 0;
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
