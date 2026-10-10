package com.pay.outbox.controller;

import com.pay.outbox.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayoutApiErrorHandlingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, 10_000L);
    }

    private ResultActions postPayout(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", CLIENT_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String payoutJson(String recipient, Object amount, String currency, String key) {
        return """
                {"recipientId":%s,"amount":%s,"currency":%s,"idempotencyKey":%s}"""
                .formatted(quote(recipient), amount, quote(currency), quote(key));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/not-a-uuid").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                .andExpect(jsonPath("$.type").value("urn:paysentinel:errors:invalid-parameter"));
    }

    @Test
    void zeroOrNegativeAmountsReturn400() throws Exception {
        postPayout(payoutJson(ACCOUNT, 0, "INR", "error-key-0001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.type").value("urn:paysentinel:errors:validation-failed"))
                .andExpect(jsonPath("$.errors.amount").exists());
        postPayout(payoutJson(ACCOUNT, -5, "INR", "error-key-0002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void invalidFieldFormatsReturn400WithFieldErrors() throws Exception {
        postPayout(payoutJson("bad id!", 100, "inr", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipientId").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.idempotencyKey").exists());
        postPayout(payoutJson(null, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipientId").exists())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void unsupportedCurrencyReturns400() throws Exception {
        postPayout(payoutJson(ACCOUNT, 100, "USD", "error-key-0003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currency").value(containsString("not supported")));
    }

    @Test
    void malformedOrMissingBodyReturns400() throws Exception {
        postPayout("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
        postPayout(payoutJson(ACCOUNT, "\"abc\"", "INR", "error-key-0004"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
        mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", CLIENT_KEY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPayoutReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/{id}", UUID.randomUUID()).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payout Not Found"));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/a/b/c").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodsReturn405() throws Exception {
        mockMvc.perform(put("/api/v1/payouts").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/payouts/{id}", UUID.randomUUID()).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentPayloadReturns409() throws Exception {
        postPayout(payoutJson(ACCOUNT, 100, "INR", "error-key-0005")).andExpect(status().isAccepted());
        postPayout(payoutJson(ACCOUNT, 100, "INR", "error-key-0005")).andExpect(status().isAccepted());
        postPayout(payoutJson(ACCOUNT, 200, "INR", "error-key-0005"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:paysentinel:errors:idempotency-key-reused"));
    }

    @Test
    void insufficientBalanceReturns422() throws Exception {
        postPayout(payoutJson(ACCOUNT, 1_000_000, "INR", "error-key-0006"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient Balance"));
    }

    @Test
    void invalidAdminParametersReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/ledger/balance/{id}", "bad!id").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountId").exists());
        mockMvc.perform(post("/api/v1/payouts/ledger/seed").param("accountId", ACCOUNT).param("amount", "-1")
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        mockMvc.perform(post("/api/v1/payouts/ledger/seed").param("accountId", ACCOUNT)
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownLedgerAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/ledger/balance/{id}", "nobody").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void unexpectedErrorsReturn500WithoutInternalDetails() throws Exception {
        doThrow(new IllegalStateException("secret-internal-detail")).when(payoutQueue).deadLetters();

        mockMvc.perform(get("/api/v1/payouts/dlq").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("secret-internal-detail"))));
    }
}
