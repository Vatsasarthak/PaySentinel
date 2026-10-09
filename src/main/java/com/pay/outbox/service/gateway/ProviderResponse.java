package com.pay.outbox.service.gateway;

import com.pay.outbox.domain.enums.PayoutStatus;

/** Final answer from the provider: COMPLETED (with a payment reference) or FAILED. */
public record ProviderResponse(PayoutStatus status, String providerReference) {
}
