-- Acknowledges a message and records it in the dead-letter queue, once per dedupe id.
-- KEYS[1] = processing list, KEYS[2] = claim timestamps hash, KEYS[3] = DLQ list, KEYS[4] = DLQ id set
-- ARGV[1] = message, ARGV[2] = dedupe id (payout id)
-- Returns 1 if a DLQ entry was added, 0 if this id was already dead-lettered.
redis.call('LREM', KEYS[1], 1, ARGV[1])
if not redis.call('LPOS', KEYS[1], ARGV[1]) then
    redis.call('HDEL', KEYS[2], ARGV[1])
end
if redis.call('SADD', KEYS[4], ARGV[2]) == 1 then
    redis.call('RPUSH', KEYS[3], ARGV[1])
    return 1
end
return 0
