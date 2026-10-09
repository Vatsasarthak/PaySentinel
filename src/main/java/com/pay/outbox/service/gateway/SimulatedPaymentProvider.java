package com.pay.outbox.service.gateway;

import com.pay.outbox.domain.enums.PayoutStatus;
import com.pay.outbox.exception.PaymentHangException;
import com.pay.outbox.metrics.PayoutMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulated provider with real-provider idempotency semantics: results are stored per idempotency
 * key (in Redis, standing in for the provider's storage), a repeated request returns the stored
 * result without executing again, and a concurrent request with the same key is refused while the
 * first is in progress. No money moves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatedPaymentProvider implements PaymentProvider {

    static final String KEY_PREFIX = "gateway:sim:payment:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration RESULT_TTL = Duration.ofDays(7);
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);

    private final GatewayOutcomeSource outcomeSource;
    private final RedisTemplate<String, String> redisTemplate;
    private final PayoutMetrics payoutMetrics;

    @Override
    public ProviderResponse submit(PaymentInstruction instruction) {
        String key = KEY_PREFIX + instruction.idempotencyKey();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, IN_PROGRESS, IN_PROGRESS_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            Optional<ProviderResponse> earlier = lookup(instruction.idempotencyKey());
            if (earlier.isPresent()) {
                log.info("Provider replayed the stored result for idempotency key {}", instruction.idempotencyKey());
                payoutMetrics.incrementGatewayReplay();
                return earlier.get();
            }
            throw new PaymentHangException("A request with this idempotency key is already in progress");
        }

        GatewayOutcome outcome = outcomeSource.next(instruction);
        return switch (outcome) {
            case SUCCESS -> store(key, new ProviderResponse(PayoutStatus.COMPLETED, newReference()));
            case DECLINED -> store(key, new ProviderResponse(PayoutStatus.FAILED, null));
            case TIMEOUT -> {
                redisTemplate.delete(key);
                throw new PaymentHangException("Payment provider timed out");
            }
            case TIMEOUT_AFTER_PROCESSING -> {
                store(key, new ProviderResponse(PayoutStatus.COMPLETED, newReference()));
                throw new PaymentHangException("Payment provider timed out after processing the payment");
            }
        };
    }

    @Override
    public Optional<ProviderResponse> lookup(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (value == null || IN_PROGRESS.equals(value)) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 2);
        String reference = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
        return Optional.of(new ProviderResponse(PayoutStatus.valueOf(parts[0]), reference));
    }

    @Override
    public boolean isInProgress(String idempotencyKey) {
        return IN_PROGRESS.equals(redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey));
    }

    private ProviderResponse store(String key, ProviderResponse response) {
        String reference = response.providerReference() != null ? response.providerReference() : "";
        redisTemplate.opsForValue().set(key, response.status().name() + "|" + reference, RESULT_TTL);
        return response;
    }

    private static String newReference() {
        return "sim_" + UUID.randomUUID().toString().replace("-", "");
    }
}
