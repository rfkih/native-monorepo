package id.co.nativeapp.restaurant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized gift-card config, bound to {@code native.giftcard} and validated at startup (fail
 * fast — ENGINEERING-STANDARDS §7).
 *
 * @param codeKey the base64-encoded 32-byte HMAC-SHA256 key {@code
 *     giftcard.domain.GiftCardCodeGenerator} keys the card-code derivation with (security review
 *     W-4). Supplied as {@code NATIVE_GIFTCARD_CODE_KEY}. <strong>Must be the IDENTICAL value in
 *     every other service that derives a gift-card code</strong> (loyalty-service +
 *     carwash-service + barbershop-service) — a mismatch would make the same card resolve to a
 *     different code depending on which service derives it.
 * @param maxMintMinor the ceiling on a single gift-card SELL's {@code amountMinor} (security review
 *     W-3) — an unbounded mint would let a compromised/misbehaving POS client create arbitrarily
 *     large redeemable liability with no independent check. Supplied as {@code
 *     NATIVE_GIFTCARD_MAX_MINT_MINOR}; a request above this ceiling is rejected {@code 422} ({@code
 *     gift-card-mint-limit}). Denominated in the request's OWN currency minor units (this service
 *     does not itself constrain currency, so the ceiling is a same-shape numeric guard, not an
 *     FX-aware one) — the default (5,000,000 IDR minor units = Rp5,000,000, since IDR's minor unit
 *     is one whole rupiah) is a conservative illustrative starting point, tunable per deployment.
 */
@Validated
@ConfigurationProperties("native.giftcard")
public record GiftCardProperties(@NotBlank String codeKey, @Min(1) long maxMintMinor) {

  /** Redacted {@code toString} — the key value must never reach a log line (rule 6). */
  @Override
  public String toString() {
    return "GiftCardProperties[codeKey=***REDACTED***, maxMintMinor=" + maxMintMinor + "]";
  }
}
