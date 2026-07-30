package id.co.nativeapp.loyalty.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized gift-card config, bound to {@code native.giftcard} and validated at startup (fail
 * fast — ENGINEERING-STANDARDS §7). Mirrors {@link PiiEncryptionProperties}'s key-sourcing
 * contract: a 12-factor env var, Vault-injected in prod, with a dev/test-only committed
 * placeholder default.
 *
 * @param codeKey the base64-encoded 32-byte HMAC-SHA256 key {@link
 *     id.co.nativeapp.loyalty.giftcard.domain.GiftCardCodeGenerator} keys the card-code derivation
 *     with (security review W-4). Supplied as {@code NATIVE_GIFTCARD_CODE_KEY} in every real
 *     environment. <strong>Must be the IDENTICAL value in every service that derives a gift-card
 *     code</strong> (loyalty-service + the three vertical services that mint cards at the till) — a
 *     mismatch would make the same card resolve to different codes depending on where it is read.
 */
@Validated
@ConfigurationProperties("native.giftcard")
public record GiftCardProperties(@NotBlank String codeKey) {

  /** Redacted {@code toString} — the key value must never reach a log line (rule 6). */
  @Override
  public String toString() {
    return "GiftCardProperties[codeKey=***REDACTED***]";
  }
}
