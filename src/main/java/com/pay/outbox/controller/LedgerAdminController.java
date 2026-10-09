package com.pay.outbox.controller;

import com.pay.outbox.dto.ValidationPatterns;
import com.pay.outbox.exception.AccountNotFoundException;
import com.pay.outbox.queue.PayoutQueue;
import com.pay.outbox.service.LedgerReconciliationService;
import com.pay.outbox.service.LedgerService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operational endpoints, restricted to the ADMIN role by {@code SecurityConfig}. ADMIN can inspect
 * any account here by design (operations and support); it cannot create payouts for accounts it was
 * not explicitly granted.
 */
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class LedgerAdminController {

    private static final int ENTRY_LIMIT = 50;

    private final LedgerService ledgerService;
    private final LedgerReconciliationService reconciliationService;
    private final PayoutQueue payoutQueue;

    @GetMapping("/ledger/balance/{accountId}")
    public ResponseEntity<Long> getBalance(
            @PathVariable @Pattern(regexp = ValidationPatterns.ACCOUNT_ID,
                    message = ValidationPatterns.ACCOUNT_ID_MESSAGE) String accountId) {
        Long balance = ledgerService.getBalance(accountId);
        if (balance == null) {
            throw new AccountNotFoundException("Ledger account not found: " + accountId);
        }
        return ResponseEntity.ok(balance);
    }

    @GetMapping("/ledger/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccount(
            @PathVariable @Pattern(regexp = ValidationPatterns.ACCOUNT_ID,
                    message = ValidationPatterns.ACCOUNT_ID_MESSAGE) String accountId) {
        LedgerService.AccountBalance account = ledgerService.getAccount(accountId);
        if (account == null) {
            throw new AccountNotFoundException("Ledger account not found: " + accountId);
        }
        return ResponseEntity.ok(Map.of(
                "accountId", account.accountId(),
                "availableBalance", account.available(),
                "heldBalance", account.held(),
                "recentEntries", ledgerService.recentEntries(accountId, ENTRY_LIMIT)));
    }

    @GetMapping("/ledger/reconciliation")
    public ResponseEntity<LedgerReconciliationService.Report> reconcile() {
        return ResponseEntity.ok(reconciliationService.reconcile());
    }

    @GetMapping("/dlq")
    public ResponseEntity<List<String>> getDlqEntries() {
        return ResponseEntity.ok(payoutQueue.deadLetters());
    }
}
