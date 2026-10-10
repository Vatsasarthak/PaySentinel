package com.pay.outbox.controller;

import com.jayway.jsonpath.JsonPath;
import com.pay.outbox.BaseIntegrationTest;
import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.dto.DecisionRequest;
import com.pay.outbox.security.ApiPrincipal;
import com.pay.outbox.service.LedgerAdjustmentService;
import com.pay.outbox.service.LedgerReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TreasuryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerAdjustmentService adjustmentService;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        ledgerService.seedBalance(ACCOUNT, 10_000L);
        ledgerService.seedBalance(OTHER_ACCOUNT, 10_000L);
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    private static String adjustment(String account, String direction, long amount, String key) {
        return """
                {"accountId":"%s","direction":"%s","amount":%d,"currency":"INR","reasonCode":"TOP_UP",
                 "note":"Wire received from merchant","externalReference":"bank-tx-42","idempotencyKey":"%s"}"""
                .formatted(account, direction, amount, key);
    }

    private ResultActions requestAdjustment(String apiKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/treasury/adjustments").header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decide(String apiKey, String id, String decision) throws Exception {
        return mockMvc.perform(post("/api/v1/treasury/adjustments/{id}/{decision}", id, decision)
                .header("X-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Checked against the bank statement\"}"));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void onlyTreasuryKeysCanReachTreasuryEndpoints() throws Exception {
        String body = adjustment(ACCOUNT, "CREDIT", 500, "funding-key-0001");
        mockMvc.perform(post("/api/v1/treasury/adjustments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        requestAdjustment(CLIENT_KEY, body).andExpect(status().isForbidden());
        requestAdjustment(ADMIN_KEY, body).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/treasury/payout-reviews").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isForbidden());
        // Treasury keys cannot create payouts either.
        mockMvc.perform(post("/api/v1/payouts").header("X-API-Key", TREASURY_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"recipientId":"test_user","amount":1,"currency":"INR","idempotencyKey":"treasury-pay-1"}"""))
                .andExpect(status().isForbidden());
        assertThat(balance()).isEqualTo(10_000L);
    }

    @Test
    void creditRequiresASecondTreasuryKeyAndPostsOnce() throws Exception {
        String id = id(requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "CREDIT", 2_500, "funding-key-0002"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.approvalRequired").value(true)));
        assertThat(balance()).isEqualTo(10_000L);

        decide(TREASURY_A_KEY, id, "approve")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(containsString("four-eyes-required")));
        assertThat(balance()).isEqualTo(10_000L);

        decide(TREASURY_B_KEY, id, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.decidedBy").value("treasury-b"));
        assertThat(balance()).isEqualTo(12_500L);

        decide(TREASURY_B_KEY, id, "approve").andExpect(status().isConflict());
        decide(TREASURY_B_KEY, id, "reject").andExpect(status().isConflict());
        assertThat(balance()).isEqualTo(12_500L);
        assertThat(ledgerService.recentEntries(ACCOUNT, 5).get(0).entryType()).isEqualTo("CREDIT");
        assertThat(auditEvents(id, "ADJUSTMENT_REQUESTED")).isEqualTo(1);
        assertThat(auditEvents(id, "ADJUSTMENT_POSTED")).isEqualTo(1);
    }

    @Test
    void creditCanOpenANewAccount() throws Exception {
        String id = id(requestAdjustment(TREASURY_A_KEY, adjustment("new_merchant", "CREDIT", 700, "funding-key-0003")));
        decide(TREASURY_B_KEY, id, "approve").andExpect(status().isOk());

        assertThat(ledgerService.getBalance("new_merchant")).isEqualTo(700L);
    }

    @Test
    void identicalRetriesReplayAndDifferentPayloadsConflict() throws Exception {
        String body = adjustment(ACCOUNT, "CREDIT", 900, "funding-key-0004");
        String first = id(requestAdjustment(TREASURY_A_KEY, body).andExpect(status().isCreated()));

        requestAdjustment(TREASURY_A_KEY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));
        requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "CREDIT", 901, "funding-key-0004"))
                .andExpect(status().isConflict());
        // The same key from a different treasury key is a different request.
        String other = id(requestAdjustment(TREASURY_B_KEY, body).andExpect(status().isCreated()));
        assertThat(other).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ledger_adjustments", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalRequestsCreateOneAdjustment() throws Exception {
        ApiPrincipal treasuryA = new ApiPrincipal("treasury-a", "TREASURY", Set.of(ACCOUNT), false, false);
        var request = new com.pay.outbox.dto.AdjustmentRequest();
        request.setAccountId(ACCOUNT);
        request.setDirection(com.pay.outbox.dto.AdjustmentRequest.Direction.CREDIT);
        request.setAmount(100L);
        request.setCurrency("INR");
        request.setReasonCode(com.pay.outbox.dto.AdjustmentRequest.ReasonCode.TOP_UP);
        request.setNote("concurrent");
        request.setIdempotencyKey("funding-key-concurrent");

        List<UUID> ids = runConcurrently(8, () -> adjustmentService.request(treasuryA, request).adjustment().id());

        assertThat(Set.copyOf(ids)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ledger_adjustments", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsPostOnce() throws Exception {
        String id = id(requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "CREDIT", 1_000, "funding-key-0005")));
        ApiPrincipal treasuryB = new ApiPrincipal("treasury-b", "TREASURY", Set.of(ACCOUNT), false, false);

        List<String> outcomes = runConcurrently(8, () -> {
            try {
                return adjustmentService.approve(treasuryB, UUID.fromString(id), "approved").status();
            } catch (com.pay.outbox.exception.StateConflictException e) {
                return "CONFLICT";
            }
        });

        assertThat(outcomes).filteredOn("POSTED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CONFLICT"::equals).hasSize(7);
        assertThat(balance()).isEqualTo(11_000L);
    }

    @Test
    void debitsCannotOverdrawOrTouchHeldFunds() throws Exception {
        createPayout("funding-payout-hold", 6_000L); // 6,000 held, 4,000 available
        String id = id(requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "DEBIT", 5_000, "funding-key-0006")));

        decide(TREASURY_B_KEY, id, "approve").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/treasury/adjustments/{id}", id).header("X-API-Key", TREASURY_B_KEY))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(balance()).isEqualTo(4_000L);
        assertThat(held()).isEqualTo(6_000L);

        decide(TREASURY_B_KEY, id, "reject").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        decide(TREASURY_B_KEY, id, "approve").andExpect(status().isConflict());

        String ok = id(requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "DEBIT", 4_000, "funding-key-0007")));
        decide(TREASURY_B_KEY, ok, "approve").andExpect(status().isOk());
        assertThat(balance()).isZero();
        assertThat(held()).isEqualTo(6_000L);
    }

    @Test
    void treasuryKeysAreLimitedToTheirAccounts() throws Exception {
        requestAdjustment(TREASURY_A_KEY, adjustment(OTHER_ACCOUNT, "CREDIT", 100, "funding-key-0008"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(containsString("account-access-denied")));
        assertThat(ledgerService.getBalance(OTHER_ACCOUNT)).isEqualTo(10_000L);
    }

    @Test
    void invalidAdjustmentsAreRejected() throws Exception {
        requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "CREDIT", 0, "funding-key-0009"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
        requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "SIDEWAYS", 10, "funding-key-0010"))
                .andExpect(status().isBadRequest());
        requestAdjustment(TREASURY_A_KEY, """
                {"accountId":"test_user","direction":"CREDIT","amount":10,"currency":"INR","reasonCode":"TOP_UP",
                 "note":"","idempotencyKey":"funding-key-0011"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        requestAdjustment(TREASURY_A_KEY, adjustment(ACCOUNT, "CREDIT", 10, "funding-key-0012").replace("INR", "USD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.currency").exists());
        assertThat(jdbcTemplate.queryForObject("select count(*) from ledger_adjustments", Integer.class)).isZero();
    }

    @Test
    void ledgerEntriesAndAuditEventsAreAppendOnly() {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET available_delta = 0"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_events"))
                .hasMessageContaining("append-only");
        assertThat(balance()).isEqualTo(10_000L);
    }

    @Test
    void manualReviewIsResolvedByTreasuryWithProviderCrossCheck() throws Exception {
        UUID refund = parkedInReview("review-key-refund");
        UUID paid = parkedInReview("review-key-paid");
        assertThat(balance()).isEqualTo(10_000L - 2_000L);

        mockMvc.perform(get("/api/v1/treasury/payout-reviews").header("X-API-Key", TREASURY_A_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/treasury/payout-reviews").header("X-API-Key", CLIENT_KEY))
                .andExpect(status().isForbidden());

        // The provider reports one payout as paid: refunding it is refused.
        redisTemplate.opsForValue().set("gateway:sim:payment:payout-" + paid, "COMPLETED|sim_ref_paid");
        resolve(paid, "{\"resolution\":\"FAILED\",\"note\":\"Customer says not received\"}")
                .andExpect(status().isConflict());
        resolve(paid, "{\"resolution\":\"COMPLETED\",\"note\":\"Provider confirms\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.providerReference").exists());
        resolve(paid, "{\"resolution\":\"COMPLETED\",\"providerReference\":\"sim_ref_paid\",\"note\":\"Provider confirms\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        // The provider has no record of the other one: an operator may refund it, exactly once.
        resolve(refund, "{\"resolution\":\"FAILED\",\"note\":\"Provider support ticket 991 confirms no payment\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        resolve(refund, "{\"resolution\":\"FAILED\",\"note\":\"again\"}").andExpect(status().isConflict());

        assertThat(balance()).isEqualTo(10_000L - 1_000L);
        assertThat(held()).isZero();
        assertThat(payout(refund).getFundsStatus()).isEqualTo(FundsStatus.RELEASED);
        assertThat(payout(paid).getFundsStatus()).isEqualTo(FundsStatus.SETTLED);
        assertThat(auditEvents(refund.toString(), "PAYOUT_REVIEW_FAILED")).isEqualTo(1);
        assertThat(auditEvents(paid.toString(), "PAYOUT_REVIEW_COMPLETED")).isEqualTo(1);
    }

    private UUID parkedInReview(String key) {
        UUID id = createPayout(key, 1_000L);
        payoutRepository.claim(id, clock.instant().plusSeconds(60), clock.instant(), 5);
        payoutRepository.markForReview(id, 1, "PROVIDER_OUTCOME_UNKNOWN", clock.instant());
        assertThat(payout(id).getStatus()).isEqualTo(PayoutStatus.MANUAL_REVIEW);
        return id;
    }

    private ResultActions resolve(UUID payoutId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/treasury/payout-reviews/{id}/resolution", payoutId)
                .header("X-API-Key", TREASURY_A_KEY).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    @SuppressWarnings("unused")
    private static DecisionRequest note(String text) {
        DecisionRequest request = new DecisionRequest();
        request.setNote(text);
        return request;
    }
}
