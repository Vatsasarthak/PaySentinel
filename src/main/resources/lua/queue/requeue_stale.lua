-- Returns an abandoned message (claimed longer than the visibility timeout ago) to the queue.
-- KEYS[1] = processing list, KEYS[2] = claim timestamps hash, KEYS[3] = queue
-- ARGV[1] = message, ARGV[2] = now (epoch millis), ARGV[3] = visibility timeout (millis)
-- Returns 1 if the message was re-queued, 0 otherwise.
local claimedAt = redis.call('HGET', KEYS[2], ARGV[1])
if not claimedAt then
    -- No timestamp recorded: start the clock now so it is re-queued on a later run if still stuck.
    redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
    return 0
end
if tonumber(ARGV[2]) - tonumber(claimedAt) < tonumber(ARGV[3]) then
    return 0
end

local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
if removed > 0 then
    redis.call('RPUSH', KEYS[3], ARGV[1])
end
if redis.call('LPOS', KEYS[1], ARGV[1]) then
    redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
else
    redis.call('HDEL', KEYS[2], ARGV[1])
end
return removed
