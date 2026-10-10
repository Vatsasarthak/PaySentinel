package com.pay.outbox.security;

import com.pay.outbox.config.PayoutProperties;
import com.pay.outbox.metrics.PayoutMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Distributed token-bucket rate limiter backed by Redis, so limits hold across all instances.
 * If Redis is unavailable the request is allowed (fail-open, the default) or refused, as configured.
 */
@Slf4j
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterMillis, boolean limiterFailed) {
    }

    private final RedisTemplate<String, String> redisTemplate;
    private final PayoutProperties properties;
    private final PayoutMetrics payoutMetrics;
    private final Clock clock;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public RateLimiter(RedisTemplate<String, String> redisTemplate, PayoutProperties properties,
                       PayoutMetrics payoutMetrics, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.payoutMetrics = payoutMetrics;
        this.clock = clock;
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/ratelimit/token_bucket.lua")));
        script.setResultType(List.class);
        // Fail at startup, not on every request, if a policy is misconfigured.
        PayoutProperties.RateLimit limits = properties.getRateLimit();
        validate("create", limits.getCreate());
        validate("read", limits.getRead());
        validate("admin", limits.getAdmin());
        validate("auth-failure", limits.getAuthFailure());
    }

    private static void validate(String name, PayoutProperties.Policy policy) {
        if (policy == null || policy.getCapacity() < 1 || policy.getRefillPerSecond() <= 0) {
            throw new IllegalStateException("payout.rate-limit." + name + " needs capacity >= 1 and refill-per-second > 0");
        }
    }

    /** Consumes one token from the bucket {@code policyName:subject}. */
    public Decision tryAcquire(String policyName, String subject, PayoutProperties.Policy policy) {
        try {
            validate(policyName, policy);
            List<?> result = evaluate("ratelimit:" + policyName + ":" + subject, policy);
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Rate-limit script returned no result");
            }
            boolean allowed = ((Number) result.get(0)).longValue() == 1L;
            if (!allowed) {
                payoutMetrics.incrementRateLimited(policyName);
            }
            return new Decision(allowed, ((Number) result.get(1)).longValue(), false);
        } catch (RuntimeException e) {
            payoutMetrics.incrementRateLimiterError();
            boolean failOpen = properties.getRateLimit().isFailOpen();
            log.warn("Rate limiter unavailable for policy {}; request {}", policyName,
                    failOpen ? "allowed (fail-open)" : "refused (fail-closed)", e);
            return new Decision(failOpen, 1000, true);
        }
    }

    /** Runs the token-bucket script. Package-private so tests can simulate a Redis failure. */
    List<?> evaluate(String bucketKey, PayoutProperties.Policy policy) {
        return redisTemplate.execute(script, List.of(bucketKey),
                String.valueOf(policy.getCapacity()),
                String.valueOf(policy.getRefillPerSecond() / 1000.0),
                String.valueOf(clock.millis()));
    }
}
