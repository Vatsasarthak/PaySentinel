-- Acknowledges one processed copy of a message.
-- KEYS[1] = processing list, KEYS[2] = claim timestamps hash
-- ARGV[1] = message
-- Returns the number of copies removed (0 or 1).
local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
if not redis.call('LPOS', KEYS[1], ARGV[1]) then
    redis.call('HDEL', KEYS[2], ARGV[1])
end
return removed
