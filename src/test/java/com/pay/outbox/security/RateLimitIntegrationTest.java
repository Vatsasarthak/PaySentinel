package com.pay.outbox.security;

import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.config.PayoutProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PayoutProperties properties;

    private final AtomicInteger keys = new AtomicInteger();
    private PayoutProperties.RateLimit original;

    @BeforeEach
    void tightenLimits() {
        ledgerService.seedBalance(ACCOUNT, 1_000_000L);
        ledgerService.seedBalance(OTHER_ACCOUNT, 1_000_000L);
        PayoutProperties.RateLimit limits = properties.getRateLimit();
        original = new PayoutProperties.RateLimit();
        original.setFailOpen(limits.isFailOpen());
        original.setCreate(limits.getCreate());
        original.setRead(limits.getRead());
        original.setAdmin(limits.getAdmin());
        original.setAuthFailure(limits.getAuthFailure());
        // 3-request bursts that refill one token per minute.
        limits.setCreate(new PayoutProperties.Policy(3, 1.0 / 60));
        limits.setAdmin(new PayoutProperties.Policy(2, 1.0 / 60));
        limits.setAuthFailure(new PayoutProperties.Policy(2, 1.0 / 60));
    }

    @AfterEach
    void restoreLimits() {
        PayoutProperties.RateLimit limits = properties.getRateLimit();
        limits.setFailOpen(original.isFailOpen());
        limits.setCreate(original.getCreate());
        limits.setRead(original.getRead());
        limits.setAdmin(original.getAdmin());
        limits.setAuthFailure(original.getAuthFailure());
    }

    private ResultActions create(String apiKey, String account) throws Exception {
        return mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"recipientId":"%s","amount":10,"currency":"INR","idempotencyKey":"ratelimit-%05d"}"""
                        .formatted(account, keys.incrementAndGet())));
    }

    @Test
    void payoutCreationIsLimitedPerClientAndRefillsOverTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            create(CLIENT_KEY, ACCOUNT).andExpect(status().isAccepted());
        }
        create(CLIENT_KEY, ACCOUNT)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("rate-limited")))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("([1-5][0-9]|60|[1-9])")));
        assertThat(payoutRepository.count()).isEqualTo(3); // the rejected request had no effect

        // Another client has its own bucket.
        create(OTHER_CLIENT_KEY, OTHER_ACCOUNT).andExpect(status().isAccepted());

        clock.advance(Duration.ofSeconds(61));
        create(CLIENT_KEY, ACCOUNT).andExpect(status().isAccepted());
        create(CLIENT_KEY, ACCOUNT).andExpect(status().isTooManyRequests());
    }

    @Test
    void readsUseASeparateBucketFromCreation() throws Exception {
        String body = create(CLIENT_KEY, ACCOUNT).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        for (int i = 0; i < 2; i++) {
            create(CLIENT_KEY, ACCOUNT);
        }
        create(CLIENT_KEY, ACCOUNT).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/payouts/{id}", id).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void adminEndpointsAreLimited() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", ADMIN_KEY)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isTooManyRequests());
        // Health stays reachable for load balancers.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void repeatedInvalidKeysFromOneAddressAreThrottled() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", "guess-guess-guess-guess-guess-" + i))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", "guess-guess-guess-guess-guess-x"))
                .andExpect(status().isTooManyRequests());
        // A valid key from the same address is unaffected.
        create(CLIENT_KEY, ACCOUNT).andExpect(status().isAccepted());
    }

    @Test
    void limiterFailureAllowsRequestsWhenFailOpenAndRefusesThemWhenFailClosed() throws Exception {
        doThrow(new RedisConnectionFailureException("simulated outage"))
                .when(rateLimiter).evaluate(anyString(), any());

        create(CLIENT_KEY, ACCOUNT).andExpect(status().isAccepted());

        properties.getRateLimit().setFailOpen(false);
        create(CLIENT_KEY, ACCOUNT)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("rate-limiter-unavailable")));
        assertThat(payoutRepository.count()).isEqualTo(1);
    }
}
