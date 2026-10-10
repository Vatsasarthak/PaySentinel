package com.pay.outbox.controller;

import com.pay.outbox.dto.ValidationPatterns;
import com.pay.outbox.service.LedgerService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import com.pay.outbox.security.ApiPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Overwrites a ledger balance; {@code amount} is in whole minor units (paise for INR). For development and testing only: the endpoint does not exist
 * unless {@code payout.ledger.seed-enabled=true}, and it always requires the ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payout.ledger.seed-enabled", havingValue = "true")
public class LedgerSeedController {

    private final LedgerService ledgerService;

    @PostMapping("/ledger/seed")
    public ResponseEntity<String> seedBalance(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestParam @Pattern(regexp = ValidationPatterns.ACCOUNT_ID,
                    message = ValidationPatterns.ACCOUNT_ID_MESSAGE) String accountId,
            @RequestParam @NotNull @PositiveOrZero @Max(ValidationPatterns.MAX_AMOUNT) Long amount) {
        ledgerService.seedBalance(accountId, amount, principal.name());
        return ResponseEntity.ok("Balance seeded: " + amount + " for account: " + accountId);
    }
}
