package id.co.nativeapp.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized operator-token signing config (ADR 0049 P1), bound to {@code native.operator-token}
 * and validated at startup (fail fast — ENGINEERING-STANDARDS §7).
 *
 * <p><strong>Shared, not auto-registered.</strong> Unlike {@code libs/security}'s own JWT
 * validation config, this record is NOT wired by {@code NativeSecurityAutoConfiguration} — it is a
 * plain shared type a service opts into explicitly via {@code @EnableConfigurationProperties(
 * OperatorTokenProperties.class)} (today: employee-service, the minter; from P2 onward, every
 * vertical that verifies a token offline).
 *
 * @param signingKey the base64-encoded HMAC-SHA256 signing key {@link OperatorTokenCodec} signs and
 *     verifies operator-session tokens with. Supplied as {@code NATIVE_OPERATOR_TOKEN_KEY}
 *     (Vault-injected in prod) — a DEDICATED key, distinct from every other service's {@code
 *     native.pii.key} / {@code native.self-order.secret-key}: this key is shared FLEET-WIDE (every
 *     vertical must be able to verify a token employee-service minted, offline — CLAUDE.md rule 2),
 *     unlike the self-order per-outlet secret or the per-service PII key.
 * @param tokenTtl how long a minted operator session stays valid before {@code exp} — a P2
 *     verifier's job to enforce, not this record's. Defaults to 12 hours (roughly a shift).
 */
@Validated
@ConfigurationProperties("native.operator-token")
public record OperatorTokenProperties(
    @NotBlank String signingKey, @DefaultValue("PT12H") @NotNull Duration tokenTtl) {

  /** Redacted {@code toString} — the key value must never reach a log line (rule 6). */
  @Override
  public String toString() {
    return "OperatorTokenProperties[signingKey=***REDACTED***, tokenTtl=" + tokenTtl + "]";
  }
}
