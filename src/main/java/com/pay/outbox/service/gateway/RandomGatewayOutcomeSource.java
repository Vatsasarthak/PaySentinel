package com.pay.outbox.service.gateway;

import com.pay.outbox.config.PayoutProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Random outcomes with the configured success/failure split; the remainder are timeouts, a
 * configured share of which happen after the provider already executed the payment.
 */
@Component
public class RandomGatewayOutcomeSource implements GatewayOutcomeSource {

    private final int successPercent;
    private final int failurePercent;
    private final int processedBeforeTimeoutPercent;

    public RandomGatewayOutcomeSource(PayoutProperties properties) {
        this.successPercent = properties.getGateway().getSuccessPercent();
        this.failurePercent = properties.getGateway().getFailurePercent();
        this.processedBeforeTimeoutPercent = properties.getGateway().getProcessedBeforeTimeoutPercent();
        if (successPercent < 0 || failurePercent < 0 || successPercent + failurePercent > 100) {
            throw new IllegalStateException("payout.gateway success/failure percentages must be >= 0 and sum to <= 100");
        }
        if (processedBeforeTimeoutPercent < 0 || processedBeforeTimeoutPercent > 100) {
            throw new IllegalStateException("payout.gateway.processed-before-timeout-percent must be between 0 and 100");
        }
    }

    @Override
    public GatewayOutcome next(PaymentInstruction instruction) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < successPercent) {
            return GatewayOutcome.SUCCESS;
        }
        if (roll < successPercent + failurePercent) {
            return GatewayOutcome.DECLINED;
        }
        return ThreadLocalRandom.current().nextInt(100) < processedBeforeTimeoutPercent
                ? GatewayOutcome.TIMEOUT_AFTER_PROCESSING
                : GatewayOutcome.TIMEOUT;
    }
}
