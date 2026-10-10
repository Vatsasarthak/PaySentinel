package com.pay.outbox.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A treasury request to credit or debit a ledger account's available balance. */
@Data
public class AdjustmentRequest {

    public enum Direction { CREDIT, DEBIT }

    public enum ReasonCode { INITIAL_FUNDING, TOP_UP, WITHDRAWAL, CORRECTION, CHARGEBACK, OTHER }

    @NotBlank(message = "Account ID is required")
    @Pattern(regexp = ValidationPatterns.ACCOUNT_ID, message = ValidationPatterns.ACCOUNT_ID_MESSAGE)
    private String accountId;

    @NotNull(message = "Direction is required")
    private Direction direction;

    /** Whole minor units of {@link #currency} (paise for INR); see ValidationPatterns. */
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than 0")
    @Max(value = ValidationPatterns.MAX_AMOUNT, message = "Amount exceeds the maximum allowed")
    private Long amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = ValidationPatterns.CURRENCY, message = ValidationPatterns.CURRENCY_MESSAGE)
    private String currency;

    @NotNull(message = "Reason code is required")
    private ReasonCode reasonCode;

    @NotBlank(message = "A note explaining the adjustment is required")
    @Size(max = 500, message = "must be at most 500 characters")
    private String note;

    @Size(max = 128, message = "must be at most 128 characters")
    @Pattern(regexp = "^[A-Za-z0-9._:/-]*$", message = "may contain letters, digits and . _ : / - only")
    private String externalReference;

    @NotBlank(message = "Idempotency key is required")
    @Pattern(regexp = ValidationPatterns.IDEMPOTENCY_KEY, message = ValidationPatterns.IDEMPOTENCY_KEY_MESSAGE)
    private String idempotencyKey;
}
