package id.co.nativeapp.restaurant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized Phase 6 (ADR 0029) self-order config, bound to {@code native.self-order} and
 * validated at startup (fail fast — ENGINEERING-STANDARDS §7). No business config is hardcoded in
 * Java.
 *
 * @param secretKey the base64-encoded 32-byte AES-256-GCM key {@link SelfOrderSecretCipher} encrypts
 *     each outlet's per-QR HMAC secret with at rest ({@code self_order_access.secret_encrypted}).
 *     Supplied as {@code NATIVE_SELFORDER_KEY} (Vault-injected in prod) — a DEDICATED key, distinct
 *     from every other service's {@code native.pii.key} / {@code native.giftcard.code-key}: this
 *     column is not fleet-shared PII, it is a per-outlet signing secret that never needs to compare
 *     equal across services.
 * @param parkCap the ceiling on a single outlet's UNCONFIRMED (PARKED, {@code source=SELF_ORDER})
 *     order count. A create request once the outlet is at/over this ceiling is rejected {@code 429}
 *     ({@code self-order-cap-exceeded}) — bounds the junk-row blast radius of an abused/leaked QR.
 * @param expiry how long a PARKED {@code SELF_ORDER} row may sit unconfirmed before the lazy sweep
 *     (run on the next create for that outlet) moves it to the terminal, non-money-moving {@code
 *     EXPIRED} status.
 * @param moduleKey the entitlement module the self-order-create path is gated on.
 * @param cacheTtl the entitlement-check Redis cache TTL safety net; invalidation by the {@code
 *     EntitlementGranted}/{@code EntitlementRevoked} events is the primary consistency mechanism,
 *     this is only the backstop.
 */
@Validated
@ConfigurationProperties("native.self-order")
public record SelfOrderProperties(
    @NotBlank String secretKey,
    @Min(1) int parkCap,
    @NotNull Duration expiry,
    @NotBlank String moduleKey,
    @NotNull Duration cacheTtl) {

  /** Redacted {@code toString} — the key value must never reach a log line (rule 6). */
  @Override
  public String toString() {
    return "SelfOrderProperties[secretKey=***REDACTED***, parkCap="
        + parkCap
        + ", expiry="
        + expiry
        + ", moduleKey="
        + moduleKey
        + ", cacheTtl="
        + cacheTtl
        + "]";
  }
}
