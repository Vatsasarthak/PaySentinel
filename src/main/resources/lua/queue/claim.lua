-- Atomically moves the next message into the processing list and records when it was claimed.
-- KEYS[1] = queue, KEYS[2] = processing list, KEYS[3] = claim timestamps hash
-- ARGV[1] = now (epoch millis)
-- Returns the message, or nil when the queue is empty.
local message = redis.call('LMOVE', KEYS[1], KEYS[2], 'LEFT', 'RIGHT')
if message then
    redis.call('HSET', KEYS[3], message, ARGV[1])
end
return message
