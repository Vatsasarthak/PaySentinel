-- Token bucket. Each call consumes one token if available.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity, ARGV[2] = refill rate (tokens per millisecond), ARGV[3] = now (epoch millis)
-- Returns {allowed (1 or 0), milliseconds until a token is available (0 when allowed)}.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
    tokens = capacity
    ts = now
end
if now > ts then
    tokens = math.min(capacity, tokens + (now - ts) * rate)
    ts = now
end

local allowed = 0
local retryAfter = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retryAfter = math.ceil((1 - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(ts))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
return {allowed, retryAfter}
