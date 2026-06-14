-- Atomic Redis token-bucket rate limiter.
--
-- KEYS[1] = the bucket key, namespaced per (company_id, sub).
-- ARGV[1] = capacity            (max tokens / burst size)
-- ARGV[2] = refill_tokens       (tokens added per refill_interval_ms)
-- ARGV[3] = refill_interval_ms  (refill period in milliseconds)
-- ARGV[4] = now_ms              (caller's clock — Redis TIME is not used so the limit is
--                                deterministic in tests and consistent with the gateway clock)
-- ARGV[5] = requested           (tokens this request costs, normally 1)
--
-- Returns { allowed (1|0), remaining_tokens }.
--
-- The bucket is two fields in a hash: `tokens` (current count) and `ts` (last refill ms).
-- On each call we lazily refill based on elapsed time, then try to take `requested` tokens.
-- A TTL keeps idle buckets from leaking; it is refreshed on every touch.

local capacity        = tonumber(ARGV[1])
local refill_tokens   = tonumber(ARGV[2])
local refill_interval = tonumber(ARGV[3])
local now             = tonumber(ARGV[4])
local requested       = tonumber(ARGV[5])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- Lazy, time-proportional refill (never exceeding capacity).
if now > ts then
  local elapsed = now - ts
  local refill = math.floor((elapsed / refill_interval) * refill_tokens)
  if refill > 0 then
    tokens = math.min(capacity, tokens + refill)
    ts = now
  end
end

local allowed = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ts)
-- Expire after enough idle time to fully refill the bucket (plus a margin), so keys self-clean.
local ttl_ms = refill_interval * math.max(1, math.ceil(capacity / refill_tokens)) * 2
redis.call('PEXPIRE', KEYS[1], ttl_ms)

return { allowed, tokens }
