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
 * <p>Three buckets, deliberately separate:
 *
 * <ul>
 *   <li>The per-tenant bucket ({@code capacity}/{@code refillTokens}/{@code refillPeriod}) is keyed
 *       per {@code (company_id, sub)} from the validated JWT and protects tenant-scoped routes.
 *   <li>The {@code signup} bucket protects the anonymous public sign-up route, where no JWT exists
 *       to key on — it is keyed per client IP instead and is tuned much tighter (a signup creates a
 *       whole tenant: company row, org unit, outbox events, Keycloak user).
 *   <li>The {@code self-order} bucket protects the anonymous self-order QR route ({@code
 *       /api/v1/self-order/**}, Phase 6, ADR 0029) — also no JWT, also keyed per client IP, but a
 *       structurally different traffic shape than signup: a single QR scan opens a whole ordering
 *       SESSION (browse the menu, edit a cart, submit, poll status) from one IP that is very often
 *       shared restaurant/mall Wi-Fi across many diners at once. Deliberately its OWN bucket (never
 *       shares {@code signup}'s key namespace) so a busy dining room can never starve — or be
 *       starved by — the tenant-creation throttle.
 * </ul>
 *
 * <p>All three are backed by Redis so the limits hold across gateway replicas. All knobs are
 * required and positive, so an unset or nonsensical limit fails fast at startup rather than
 * silently disabling the limiter.
 */
@Validated
@ConfigurationProperties("native.gateway.rate-limit")
public record RateLimitProperties(
    @Min(1) long capacity,
    @Min(1) long refillTokens,
    @NotNull Duration refillPeriod,
    @NotNull @Valid AnonymousBucket signup,
    @NotNull @Valid AnonymousBucket selfOrder,
    @NotNull @Valid AnonymousBucket pspWebhook) {

  /**
   * An anonymous, per-client-IP bucket shape shared by every unauthenticated route ({@code signup},
   * {@code self-order}, ...). Each route gets its OWN instance (its own config prefix and its own
   * Redis key namespace via {@code AnonymousRateLimitFilter}'s constructor) so the routes can never
   * share — or drain — one another's budget.
   *
   * @param capacity bucket size (max burst from one IP)
   * @param refillTokens tokens restored every {@code refillPeriod}
   * @param refillPeriod the refill window
   * @param trustForwardedFor when {@code true}, the client IP is taken from the LAST entry of
   *     {@code X-Forwarded-For} (the one appended by the trusted ingress in front of the gateway);
   *     when {@code false} (default — no trusted proxy), the socket's remote address is used and
   *     {@code X-Forwarded-For} is IGNORED, so a client cannot spoof its way into fresh buckets.
   *     Only enable behind an ingress that overwrites/appends the header.
   */
  public record AnonymousBucket(
      @Min(1) long capacity,
      @Min(1) long refillTokens,
      @NotNull Duration refillPeriod,
      boolean trustForwardedFor) {}
}
