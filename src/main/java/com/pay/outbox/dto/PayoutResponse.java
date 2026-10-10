package com.pay.outbox.dto;

import com.pay.outbox.domain.enums.FundsStatus;
import com.pay.outbox.domain.enums.PayoutStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PayoutResponse {

    private UUID id;
    private String recipientId;
    /** Whole minor units (paise for INR). */
    private Long amount;
    private String currency;
    private PayoutStatus status;
    private FundsStatus fundsStatus;
    private String failureReason;
    private String providerReference;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer attempts;
}
