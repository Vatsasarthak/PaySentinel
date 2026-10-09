package com.pay.outbox.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PayoutRequest {

    @NotBlank(message = "Recipient ID is required")
    @Pattern(regexp = ValidationPatterns.ACCOUNT_ID, message = ValidationPatterns.ACCOUNT_ID_MESSAGE)
    private String recipientId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than 0")
    @Max(value = ValidationPatterns.MAX_AMOUNT, message = "Amount exceeds the maximum allowed")
    private Long amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = ValidationPatterns.CURRENCY, message = ValidationPatterns.CURRENCY_MESSAGE)
    private String currency;

    @NotBlank(message = "Idempotency key is required")
    @Pattern(regexp = ValidationPatterns.IDEMPOTENCY_KEY, message = ValidationPatterns.IDEMPOTENCY_KEY_MESSAGE)
    private String idempotencyKey;
}
