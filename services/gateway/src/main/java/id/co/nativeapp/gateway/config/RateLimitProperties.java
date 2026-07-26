package id.co.nativeapp.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token-bucket knobs (externalized — ENGINEERING-STANDARDS §7).
 *
 * <p>Two buckets, deliberately separate:
 *
 * <ul>
 *   <li>The per-tenant bucket ({@code capacity}/{@code refillTokens}/{@code refillPeriod}) is keyed
 *       per {@code (company_id, sub)} from the validated JWT and protects tenant-scoped routes.
 *   <li>The {@code signup} bucket protects the anonymous public sign-up route, where no JWT exists
 *       to key on — it is keyed per client IP instead and is tuned much tighter (a signup creates a
 *       whole tenant: company row, org unit, outbox events, Keycloak user).
 * </ul>
 *
 * <p>Both are backed by Redis so the limits hold across gateway replicas. All knobs are required
 * and positive, so an unset or nonsensical limit fails fast at startup rather than silently
 * disabling the limiter.
 */
@Validated
@ConfigurationProperties("native.gateway.rate-limit")
public record RateLimitProperties(
    @Min(1) long capacity,
    @Min(1) long refillTokens,
    @NotNull Duration refillPeriod,
    @NotNull @Valid Signup signup) {

  /**
   * The anonymous signup bucket, keyed per client IP.
   *
   * @param capacity bucket size (max burst of signups from one IP)
   * @param refillTokens tokens restored every {@code refillPeriod}
   * @param refillPeriod the refill window
   * @param trustForwardedFor when {@code true}, the client IP is taken from the LAST entry of
   *     {@code X-Forwarded-For} (the one appended by the trusted ingress in front of the gateway);
   *     when {@code false} (default — no trusted proxy), the socket's remote address is used and
   *     {@code X-Forwarded-For} is IGNORED, so a client cannot spoof its way into fresh buckets.
   *     Only enable behind an ingress that overwrites/appends the header.
   */
  public record Signup(
      @Min(1) long capacity,
      @Min(1) long refillTokens,
      @NotNull Duration refillPeriod,
      boolean trustForwardedFor) {}
}
