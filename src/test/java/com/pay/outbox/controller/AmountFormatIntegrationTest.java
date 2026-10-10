package com.pay.outbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.dto.PayoutMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Amounts are whole minor units (paise for INR). A fractional JSON number must be rejected with 400,
 * never truncated, and must not create anything.
 */
class AmountFormatIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, 100_000L); // 100,000 paise = Rs 1,000.00
    }

    private ResultActions payout(String amountLiteral, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", CLIENT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"test_user\",\"amount\":" + amountLiteral
                        + ",\"currency\":\"INR\",\"idempotencyKey\":\"" + idempotencyKey + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"50.75", "0.99", "5000.0", "5e3", "1.5E2", "\"50.75\"", "\"0.99\""})
    void fractionalOrFloatingPointPayoutAmountsAreRejectedWith400AndCreateNothing(String amount) throws Exception {
        payout(amount, "amount-fraction-0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));

        assertThat(payoutRepository.count()).isZero();
        assertThat(balance()).isEqualTo(100_000L);
        assertThat(held()).isZero();
    }

    @Test
    void fractionalTreasuryAdjustmentAmountsAreRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/treasury/adjustments").header("X-API-Key", TREASURY_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"accountId":"test_user","direction":"CREDIT","amount":50.75,"currency":"INR",
                                 "reasonCode":"TOP_UP","note":"fractional","idempotencyKey":"amount-fraction-0002"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from ledger_adjustments", Integer.class)).isZero();
    }

    @Test
    void fractionalSeedAmountIsRejectedWith400() throws Exception {
        // Query parameters are converted by Spring MVC, not Jackson; they were already strict.
        mockMvc.perform(post("/api/v1/payouts/ledger/seed").param("accountId", ACCOUNT).param("amount", "50.75")
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        assertThat(balance()).isEqualTo(100_000L);
    }

    @Test
    void integerAmountsStillWork() throws Exception {
        payout("5075", "amount-integer-0001") // 5075 paise = Rs 50.75
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amount").value(5075));

        assertThat(balance()).isEqualTo(100_000L - 5_075L);
        assertThat(held()).isEqualTo(5_075L);
    }

    @Test
    void unrelatedDeserializationBehaviourIsUnchanged() throws Exception {
        // A whole number sent as a JSON string is still coerced, exactly as before this change.
        payout("\"1200\"", "amount-string-0001")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amount").value(1200));
        // Unknown properties are still ignored (Spring Boot default).
        mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", CLIENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"recipientId":"test_user","amount":300,"currency":"INR",
                                 "idempotencyKey":"amount-unknown-0001","comment":"ignored"}"""))
                .andExpect(status().isAccepted());
        // Non-numeric amounts are still malformed.
        payout("\"abc\"", "amount-abc-0001").andExpect(status().isBadRequest());

        assertThat(balance()).isEqualTo(100_000L - 1_200L - 300L);
    }

    @Test
    void queueMessagesStillParseAndFractionalOnesAreRejected() throws Exception {
        UUID id = UUID.randomUUID();
        // Messages written by this and earlier versions carry integer amounts.
        PayoutMessage message = objectMapper.readValue(
                "{\"payoutId\":\"" + id + "\",\"recipientId\":\"test_user\",\"amount\":5000,\"currency\":\"INR\"}",
                PayoutMessage.class);
        assertThat(message).isEqualTo(new PayoutMessage(id, "test_user", 5000L, "INR"));

        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"payoutId\":\"" + id + "\",\"recipientId\":\"test_user\",\"amount\":50.75,\"currency\":\"INR\"}",
                PayoutMessage.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }
}
