package com.pay.outbox.controller;

import com.pay.outbox.dto.PayoutRequest;
import com.pay.outbox.dto.PayoutResponse;
import com.pay.outbox.exception.AccountAccessDeniedException;
import com.pay.outbox.exception.PayoutNotFoundException;
import com.pay.outbox.security.ApiPrincipal;
import com.pay.outbox.service.PayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Payout API. Every call is authorized against the caller's granted ledger accounts: a payout can
 * only be created for an account the API key may use, and a payout on any other account is reported
 * as not found so its existence is not revealed.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    @PostMapping
    public ResponseEntity<PayoutResponse> initiatePayout(@AuthenticationPrincipal ApiPrincipal principal,
                                                         @Valid @RequestBody PayoutRequest request) {
        if (!principal.mayUseAccount(request.getRecipientId())) {
            log.warn("Client {} attempted a payout on account {} it is not authorized for", principal.name(),
                    request.getRecipientId());
            throw new AccountAccessDeniedException("This API key is not authorized for account "
                    + request.getRecipientId());
        }
        log.info("Received payout request from client {} for account {}", principal.name(), request.getRecipientId());
        PayoutResponse response = payoutService.initiatePayout(principal.name(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayoutResponse> getPayoutById(@AuthenticationPrincipal ApiPrincipal principal,
                                                        @PathVariable UUID id) {
        PayoutResponse response = payoutService.getPayoutById(id);
        if (!principal.mayReadAccount(response.getRecipientId())) {
            log.warn("Client {} requested payout {} on an account it is not authorized for", principal.name(), id);
            throw new PayoutNotFoundException("Payout not found with id: " + id);
        }
        return ResponseEntity.ok(response);
    }
}
