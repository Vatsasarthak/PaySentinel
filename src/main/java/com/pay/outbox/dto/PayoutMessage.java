package com.pay.outbox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Queue message published by the outbox relay for each accepted payout. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutMessage(UUID payoutId, String recipientId, long amount, String currency) {
}
