package com.pay.outbox.service.gateway;

/** Decides what the simulated provider does with a new request. Tests replace it with a deterministic stub. */
public interface GatewayOutcomeSource {

    GatewayOutcome next(PaymentInstruction instruction);
}
