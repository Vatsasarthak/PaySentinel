package com.pay.outbox.controller;

import com.jayway.jsonpath.JsonPath;
import com.pay.outbox.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayoutApiSecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, 10_000L);
        ledgerService.seedBalance(OTHER_ACCOUNT, 10_000L);
        ledgerService.seedBalance(SHARED_ACCOUNT, 10_000L);
    }

    private ResultActions createPayout(String apiKey, String account, String idempotencyKey) throws Exception {
        var request = post("/api/v1/payouts").contentType(MediaType.APPLICATION_JSON).content("""
                {"recipientId":"%s","amount":100,"currency":"INR","idempotencyKey":"%s"}"""
                .formatted(account, idempotencyKey));
        return mockMvc.perform(apiKey == null ? request : request.header("X-API-Key", apiKey));
    }

    private String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void requestWithoutApiKeyIsRejectedWith401ProblemDetail() throws Exception {
        createPayout(null, ACCOUNT, "security-key-01")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", containsString("X-API-Key")))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void requestWithInvalidApiKeyIsRejectedWith401() throws Exception {
        createPayout("wrong-key-wrong-key-wrong-key-wrong", ACCOUNT, "security-key-02")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", "wrong-key-wrong-key-wrong-key-wrong"))
                .andExpect(status().isUnauthorized());
        assertNoPayouts();
    }

    @Test
    void clientCanCreateAndReadPayoutsOnItsOwnAccount() throws Exception {
        String id = createdId(createPayout(CLIENT_KEY, ACCOUNT, "security-key-03")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING")));

        mockMvc.perform(get("/api/v1/payouts/{id}", id).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void clientCannotCreatePayoutsOnAnotherClientsAccount() throws Exception {
        createPayout(CLIENT_KEY, OTHER_ACCOUNT, "security-key-04")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(containsString("account-access-denied")));

        assertNoPayouts();
        org.assertj.core.api.Assertions.assertThat(ledgerService.getBalance(OTHER_ACCOUNT)).isEqualTo(10_000L);
    }

    @Test
    void clientCannotReadPayoutsOfAnotherClientsAccount() throws Exception {
        String othersPayout = createdId(createPayout(OTHER_CLIENT_KEY, OTHER_ACCOUNT, "security-key-05")
                .andExpect(status().isAccepted()));

        // Reported as not found so that the payout's existence is not revealed.
        mockMvc.perform(get("/api/v1/payouts/{id}", othersPayout).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payout Not Found"));
    }

    @Test
    void sharedAccountIsUsableByBothClientsWithSeparateIdempotencyKeys() throws Exception {
        String first = createdId(createPayout(CLIENT_KEY, SHARED_ACCOUNT, "security-key-06")
                .andExpect(status().isAccepted()));
        String second = createdId(createPayout(OTHER_CLIENT_KEY, SHARED_ACCOUNT, "security-key-06")
                .andExpect(status().isAccepted()));

        org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
        mockMvc.perform(get("/api/v1/payouts/{id}", first).header("X-API-Key", OTHER_CLIENT_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void adminDoesNotBypassAccountOwnership() throws Exception {
        createPayout(ADMIN_KEY, ACCOUNT, "security-key-07")
                .andExpect(status().isForbidden());
        String id = createdId(createPayout(CLIENT_KEY, ACCOUNT, "security-key-08"));

        mockMvc.perform(get("/api/v1/payouts/{id}", id).header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void clientCannotUseAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));
        mockMvc.perform(get("/api/v1/payouts/ledger/balance/{id}", ACCOUNT).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payouts/ledger/accounts/{id}", ACCOUNT).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payouts/ledger/reconciliation").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payouts/ledger/seed").param("accountId", ACCOUNT).param("amount", "999999")
                        .header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(balance()).isEqualTo(10_000L);
    }

    @Test
    void adminCanUseAdminEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/payouts/ledger/seed").param("accountId", "admin_seeded").param("amount", "500")
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/payouts/ledger/balance/{id}", "admin_seeded").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("500"));
        mockMvc.perform(get("/api/v1/payouts/ledger/accounts/{id}", "admin_seeded").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(500))
                .andExpect(jsonPath("$.heldBalance").value(0))
                .andExpect(jsonPath("$.recentEntries[0].entryType").value("ADJUSTMENT"));
        mockMvc.perform(get("/api/v1/payouts/ledger/reconciliation").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/actuator/metrics").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsPublicButHidesDetailsFromAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void actuatorEndpointsOtherThanHealthAndInfoRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
        // The writable loggers endpoint is only exposed by the dev profile.
        mockMvc.perform(get("/actuator/loggers").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    private void assertNoPayouts() {
        org.assertj.core.api.Assertions.assertThat(payoutRepository.count()).isZero();
    }
}
