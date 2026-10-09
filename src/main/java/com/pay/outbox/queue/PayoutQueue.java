package com.pay.outbox.queue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reliable Redis list queue. A consumer atomically moves a message into a processing list
 * ({@code LMOVE}) and removes it only after the outcome is durably recorded in PostgreSQL. Messages
 * left in the processing list by a crashed consumer are re-queued after a visibility timeout.
 * Delivery is at-least-once; consumers must be idempotent.
 */
@Component
public class PayoutQueue {

    public static final String QUEUE_KEY = "payout:queue";
    public static final String PROCESSING_KEY = "payout:processing";
    public static final String CLAIMS_KEY = "payout:processing:claims";
    public static final String DLQ_KEY = "payout:dlq";
    public static final String DLQ_IDS_KEY = "payout:dlq:ids";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<String> claimScript = script("lua/queue/claim.lua", String.class);
    private final DefaultRedisScript<Long> ackScript = script("lua/queue/ack.lua", Long.class);
    private final DefaultRedisScript<Long> deadLetterScript = script("lua/queue/dead_letter.lua", Long.class);
    private final DefaultRedisScript<Long> requeueScript = script("lua/queue/requeue_stale.lua", Long.class);

    public PayoutQueue(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String message) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
    }

    /** Claims the next message, or returns null when the queue is empty. */
    public String claimNext(Instant now) {
        return redisTemplate.execute(claimScript, List.of(QUEUE_KEY, PROCESSING_KEY, CLAIMS_KEY),
                String.valueOf(now.toEpochMilli()));
    }

    public void ack(String message) {
        redisTemplate.execute(ackScript, List.of(PROCESSING_KEY, CLAIMS_KEY), message);
    }

    /** Acknowledges the message and adds it to the DLQ unless {@code dedupeId} is already there. */
    public boolean deadLetter(String message, String dedupeId) {
        Long added = redisTemplate.execute(deadLetterScript,
                List.of(PROCESSING_KEY, CLAIMS_KEY, DLQ_KEY, DLQ_IDS_KEY), message, dedupeId);
        return added != null && added == 1L;
    }

    public boolean requeueIfStale(String message, Instant now, Duration visibilityTimeout) {
        Long requeued = redisTemplate.execute(requeueScript, List.of(PROCESSING_KEY, CLAIMS_KEY, QUEUE_KEY),
                message, String.valueOf(now.toEpochMilli()), String.valueOf(visibilityTimeout.toMillis()));
        return requeued != null && requeued > 0;
    }

    public List<String> processingSnapshot(int limit) {
        return nullSafe(redisTemplate.opsForList().range(PROCESSING_KEY, 0, limit - 1L));
    }

    /** Whether the message is waiting in the queue or currently being processed. */
    public boolean isInFlight(String message) {
        return redisTemplate.opsForList().indexOf(QUEUE_KEY, message) != null
                || redisTemplate.opsForList().indexOf(PROCESSING_KEY, message) != null;
    }

    public List<String> deadLetters() {
        return nullSafe(redisTemplate.opsForList().range(DLQ_KEY, 0, -1));
    }

    public long queuedCount() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    public long processingCount() {
        Long size = redisTemplate.opsForList().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }

    private static List<String> nullSafe(List<String> values) {
        return values != null ? values : List.of();
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}
