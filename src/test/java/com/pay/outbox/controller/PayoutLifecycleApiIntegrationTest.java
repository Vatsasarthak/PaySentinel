package com.pay.outbox.controller;

import com.jayway.jsonpath.JsonPath;
import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.scheduler.OutboxRelayScheduler;
import com.pay.outbox.service.gateway.GatewayOutcome;
import com.pay.outbox.worker.PaymentProcessingWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The documented API flow end to end, as a client and an operator would see it: create a payout,
 * replay it, reuse its key with a different payload, let the outbox relay and worker run, poll it, and
 * check the balance and reconciliation. The provider outcome is stubbed so both paths are deterministic.
 * Amounts are paise: 100,000 = Rs 1,000.00 and 5,000 = Rs 50.00.
 */
class PayoutLifecycleApiIntegrationTest extends BaseIntegrationTest {

    private static final long SEED = 100_000L;
    private static final long AMOUNT = 5_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxRelayScheduler relay;

    @Autowired
    private PaymentProcessingWorker worker;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, SEED);
    }

    private ResultActions submit(String idempotencyKey, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", CLIENT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"test_user\",\"amount\":" + amount
                        + ",\"currency\":\"INR\",\"idempotencyKey\":\"" + idempotencyKey + "\"}"));
    }

    /** Creates the payout and checks replay and conflict handling before any processing happens. */
    private String createReplayAndConflict(String idempotencyKey) throws Exception {
        String body = submit(idempotencyKey, AMOUNT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.fundsStatus").value("HELD"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        submit(idempotencyKey, AMOUNT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id));
        submit(idempotencyKey, AMOUNT + 1_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("idempotency-key-reused")));

        assertThat(payoutRepository.count()).isEqualTo(1);
        assertThat(ledgerEntries(java.util.UUID.fromString(id), "HOLD")).isEqualTo(1);
        assertBalance(SEED - AMOUNT, AMOUNT);
        return id;
    }

    private void assertBalance(long available, long held) throws Exception {
        mockMvc.perform(get("/api/v1/payouts/ledger/accounts/{account}", ACCOUNT).header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(available))
                .andExpect(jsonPath("$.heldBalance").value(held));
    }

    private void assertReconciled() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/ledger/reconciliation").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.mismatchCount").value(0));
    }

    private List<String> entryTypes(String payoutId) {
        return jdbcTemplate.queryForList(
                "select entry_type from ledger_entries where payout_id = ?::uuid order by id", String.class, payoutId);
    }

    @Test
    void completedPayoutLifecycleThroughTheApi() throws Exception {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.SUCCESS);
        String id = createReplayAndConflict("lifecycle-success-0001");

        relay.relay();
        worker.drainOnce();

        mockMvc.perform(get("/api/v1/payouts/{id}", id).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fundsStatus").value("SETTLED"))
                .andExpect(jsonPath("$.providerReference").value(org.hamcrest.Matchers.startsWith("sim_")));
        assertBalance(SEED - AMOUNT, 0);
        assertThat(entryTypes(id)).containsExactly("HOLD", "SETTLE");
        assertReconciled();

        // A client retrying after the payout finished gets the final state, and nothing moves again.
        submit("lifecycle-success-0001", AMOUNT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        worker.drainOnce();
        assertThat(payoutRepository.count()).isEqualTo(1);
        assertThat(entryTypes(id)).containsExactly("HOLD", "SETTLE");
        assertBalance(SEED - AMOUNT, 0);
        verify(gatewayOutcomes, times(1)).next(any());
    }

    @Test
    void declinedPayoutLifecycleReleasesTheHoldThroughTheApi() throws Exception {
        when(gatewayOutcomes.next(any())).thenReturn(GatewayOutcome.DECLINED);
        String id = createReplayAndConflict("lifecycle-decline-0001");

        relay.relay();
        worker.drainOnce();

        mockMvc.perform(get("/api/v1/payouts/{id}", id).header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.fundsStatus").value("RELEASED"))
                .andExpect(jsonPath("$.failureReason").value("DECLINED"));
        assertBalance(SEED, 0);
        assertThat(entryTypes(id)).containsExactly("HOLD", "RELEASE");
        assertReconciled();

        submit("lifecycle-decline-0001", AMOUNT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("FAILED"));
        worker.drainOnce();
        assertThat(entryTypes(id)).containsExactly("HOLD", "RELEASE");
        assertBalance(SEED, 0);
        verify(gatewayOutcomes, times(1)).next(any());
    }
}
