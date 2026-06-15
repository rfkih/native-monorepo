package id.co.nativeapp.gateway.ratelimit;

import id.co.nativeapp.gateway.config.RateLimitProperties;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis-backed atomic token bucket — the per-tenant rate limit, distributed across gateway
 * replicas.
 *
 * <p>The whole take-and-refill decision runs inside a single Lua script ({@code
 * redis/token-bucket.lua}), so it is atomic even under concurrent requests and shared across every
 * gateway instance pointed at the same Redis. Each {@code (company_id, sub)} pair gets its own
 * bucket key; exceeding it returns {@code false}, which the filter translates to {@code 429}.
 *
 * <p><strong>Fail-closed.</strong> If Redis is unreachable (or the script errors), the limiter
 * cannot prove a request is within budget, so it <em>denies</em> the request (returns {@code false}
 * → {@code 429}) rather than failing open and letting an unbounded flood through with the limit
 * silently disabled (ENGINEERING-STANDARDS §4/§6). The error is logged (no token/secret), never
 * swallowed silently.
 *
 * <p>The clock is injected so tests are deterministic (no wall-clock flakiness) and so the limit is
 * computed against the gateway's clock rather than Redis's.
 */
@Component
public class RedisTokenBucketRateLimiter {

  private static final String KEY_PREFIX = "native:gw:rl:";

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

  private final StringRedisTemplate redis;
  private final RedisScript<List> script;
  private final RateLimitProperties props;
  private final Clock clock;

  public RedisTokenBucketRateLimiter(
      RedisConnectionFactory connectionFactory, RateLimitProperties props, Clock clock) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(connectionFactory);
    template.afterPropertiesSet();
    this.redis = template;
    this.props = props;
    this.clock = clock;
    DefaultRedisScript<List> s = new DefaultRedisScript<>();
    s.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token-bucket.lua")));
    s.setResultType(List.class);
    this.script = s;
  }

  /**
   * Atomically attempts to take one token from the bucket for {@code (companyId, sub)}.
   *
   * <p><strong>Fail-closed:</strong> if Redis is unreachable or the script fails, this returns
   * {@code false} (deny), so a Redis outage tightens — never disables — the limit.
   *
   * @return {@code true} if the request is within the limit, {@code false} if it must be rejected
   *     with {@code 429} (including when Redis is unavailable).
   */
  public boolean tryAcquire(String companyId, String sub) {
    String key = KEY_PREFIX + companyId + ':' + sub;
    long now = clock.millis();
    try {
      @SuppressWarnings("unchecked")
      List<Long> result =
          redis.execute(
              script,
              List.of(key),
              Long.toString(props.capacity()),
              Long.toString(props.refillTokens()),
              Long.toString(props.refillPeriod().toMillis()),
              Long.toString(now),
              "1");
      return result != null && !result.isEmpty() && result.get(0) == 1L;
    } catch (DataAccessException ex) {
      // Redis down / script error: fail CLOSED (deny) so the limit can never be silently disabled.
      // Log the class only — never the key, token, or any tenant/secret value (HR-6).
      log.warn(
          "Rate-limit backend unavailable ({}); failing closed (deny).",
          ex.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * The {@code Retry-After} hint (seconds) a {@code 429} response advertises — the refill period
   * rounded up to whole seconds (at least 1), so a client backs off until at least one token is
   * available again.
   */
  public long retryAfterSeconds() {
    long seconds = props.refillPeriod().toSeconds();
    return Math.max(1L, seconds);
  }
}
