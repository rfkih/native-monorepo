package id.co.nativeapp.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exchange rate as an EXACT scaled-integer pair plus the ordered currency pair it converts, and
 * the {@link RateType} / {@link Provenance} it carries.
 *
 * <p><strong>No float, ever.</strong> The rate's value is {@code unscaled / 10^scale} — two
 * integers ({@code long unscaled}, {@code int scale}) — so it is exact and reproducible; no binary
 * floating-point ever touches it. E.g. {@code 1 USD = 16,250.000000 IDR} is {@code unscaled =
 * 16_250_000_000}, {@code scale = 6}. This is a plain immutable {@code record} value type — NOT a
 * JPA {@code @Entity}/{@code @Embeddable} — so the anti-float ArchUnit field scan does not apply to
 * it; even so it holds NO {@code BigDecimal} field, only the two integers and the two currencies.
 *
 * <p><strong>The quote convention is MAJOR-per-major:</strong> {@code unscaled / 10^scale} major
 * units of {@link #to()} per 1 major unit of {@link #from()} (the everyday "1 USD = 16,000 IDR"
 * reading).
 *
 * <p><strong>Conversion is the one place currencies cross.</strong> {@link #convert(Money,
 * Currency)} asserts the source currency equals {@link #from()} (a mismatch throws {@link
 * MismatchedCurrencyException}, mirroring {@link Money#requireSameCurrencyAs(Money)} -- there is no
 * implicit FX), then computes the result TRANSIENTLY in memory with the exact minor-to-minor
 * formula:
 *
 * <pre>{@code
 * target_minor = source_minor * unscaled * 10^toFractionDigits
 *                ----------------------------------------------   (round once, HALF_EVEN)
 *                          10^scale * 10^fromFractionDigits
 * }</pre>
 *
 * <p>The {@code 10^toFrac / 10^fromFrac} factor is exactly what makes the cross-fraction-digit case
 * (USD 2dp to IDR 0dp) correct -- e.g. 100 USD ({@code source_minor = 10_000}) at 16,000 IDR/USD
 * ({@code unscaled = 16_000, scale = 0}) yields {@code 10_000 * 16_000 * 10^0 / (10^0 * 10^2) =
 * 1_600_000} IDR minor. The numerator multiply is exact (no intermediate rounding); only the single
 * final divide rounds {@link RoundingMode#HALF_EVEN}. {@link BigDecimal} appears only as a local
 * stack variable here -- exactly like {@link Money#mulDiv(long, long)} -- never a field, never
 * persisted, never returned. The result is a real {@link Money} in the target currency; two runs
 * with the same inputs always agree.
 *
 * @param unscaled the rate's unscaled integer value (strictly positive); value = {@code unscaled /
 *     10^scale}
 * @param scale the rate's decimal scale (non-negative) -- how many powers of ten {@code unscaled}
 *     is divided by
 * @param from the source currency this rate converts FROM
 * @param to the target currency this rate converts TO
 * @param rateType the IAS-21 rate kind (CLOSING / AVERAGE / SPOT)
 * @param provenance the trust level (STUB / LIVE / OFFICIAL) -- drives the {@code uses_stub_fx}
 *     flag
 */
public record FxRate(
    long unscaled,
    int scale,
    Currency from,
    Currency to,
    RateType rateType,
    Provenance provenance) {

  /** Compact canonical constructor -- validates the integer pair and the currencies. */
  public FxRate {
    if (unscaled <= 0L) {
      throw new IllegalArgumentException("FxRate unscaled must be strictly positive: " + unscaled);
    }
    if (scale < 0) {
      throw new IllegalArgumentException("FxRate scale must be non-negative: " + scale);
    }
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(rateType, "rateType");
    Objects.requireNonNull(provenance, "provenance");
  }

  /**
   * The identity (self) rate for a currency: {@code unscaled = 1, scale = 0}, {@code from == to ==
   * currency}. Used to short-circuit a same-currency conversion with no DB round-trip; never
   * stored, never queried.
   *
   * @param currency the currency the identity rate maps to itself
   * @param rateType the rate kind to stamp (so the applied-rate audit trail is consistent)
   * @param provenance the provenance to stamp -- {@link Provenance#OFFICIAL} for an identity (a
   *     same-currency "conversion" is never stub-contaminated by the rate itself)
   * @return the identity {@code FxRate} for {@code currency}
   */
  public static FxRate identity(Currency currency, RateType rateType, Provenance provenance) {
    return new FxRate(1L, 0, currency, currency, rateType, provenance);
  }

  /**
   * Converts {@code source} into {@code target} at this rate, returning a {@link Money} in {@code
   * target}, rounded ONCE with {@link RoundingMode#HALF_EVEN} to the target currency's fraction
   * digits. The arithmetic is exact: the numerator multiply carries no intermediate rounding and
   * only the single final divide rounds, so the result is deterministic and reproducible.
   *
   * @param source the amount to convert; its currency MUST equal {@link #from()}
   * @param target the currency to convert into; MUST equal {@link #to()}
   * @return the converted amount as {@link Money} in {@code target}
   * @throws MismatchedCurrencyException if {@code source.currency()} is not {@link #from()}
   * @throws IllegalArgumentException if {@code target} is not {@link #to()}
   * @throws ArithmeticException if the rounded result overflows a {@code long} minor amount
   */
  public Money convert(Money source, Currency target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    if (!source.currency().equals(from)) {
      throw new MismatchedCurrencyException(from, source.currency());
    }
    if (!target.equals(to)) {
      throw new IllegalArgumentException(
          "FxRate converts to "
              + to.getCurrencyCode()
              + " but target is "
              + target.getCurrencyCode());
    }
    int fromFrac = Money.fractionDigits(from);
    int toFrac = Money.fractionDigits(to);
    // Transient BigDecimal multiply-then-single-divide -- the SAME discipline as Money.mulDiv.
    //   numerator = source_minor * unscaled * 10^toFrac     (exact, no rounding)
    //   divisor   = 10^scale * 10^fromFrac
    //   target_minor = round(numerator / divisor, 0, HALF_EVEN)
    BigDecimal numerator =
        BigDecimal.valueOf(source.amountMinor())
            .multiply(BigDecimal.valueOf(unscaled))
            .multiply(BigDecimal.TEN.pow(toFrac));
    BigDecimal divisor = BigDecimal.TEN.pow(scale).multiply(BigDecimal.TEN.pow(fromFrac));
    long roundedMinor = numerator.divide(divisor, 0, RoundingMode.HALF_EVEN).longValueExact();
    return Money.ofMinor(roundedMinor, target);
  }

  /**
   * Composes this leg ({@code from -> pivot}) with a {@code pivot -> to} leg into a single exact
   * {@code from -> to} rate by multiplying the integer ratios: {@code unscaled = unscaled1 *
   * unscaled2}, {@code scale = scale1 + scale2}. This is how a triangulated conversion (a missing
   * direct pair pivoted via, e.g., USD) stays exact: the two integer ratios are composed FIRST and
   * the resulting single rate rounds ONCE at the target in {@link #convert(Money, Currency)} -- an
   * intermediate pivot amount is never rounded into a coarse currency and back.
   *
   * <p>The composed rate's {@link RateType} is this leg's (both legs are resolved at the same rate
   * type by the caller). The composed {@link Provenance} is the LOUDER of the two: if EITHER leg is
   * {@link Provenance#STUB}, the result is STUB (stub-contaminated if either leg is a stub),
   * mirroring the {@code uses_stub_fx} OR-across-legs rule.
   *
   * @param next the second leg; its {@link #from()} MUST equal this leg's {@link #to()} (the pivot)
   * @return the composed {@code this.from -> next.to} rate
   * @throws IllegalArgumentException if the legs do not meet at a common pivot currency
   * @throws ArithmeticException if the composed {@code unscaled} overflows a {@code long}
   */
  public FxRate composeWith(FxRate next) {
    Objects.requireNonNull(next, "next");
    if (!this.to.equals(next.from)) {
      throw new IllegalArgumentException(
          "Cannot compose FX legs: "
              + this.from.getCurrencyCode()
              + "->"
              + this.to.getCurrencyCode()
              + " then "
              + next.from.getCurrencyCode()
              + "->"
              + next.to.getCurrencyCode()
              + " (pivot mismatch)");
    }
    long composedUnscaled = Math.multiplyExact(this.unscaled, next.unscaled);
    int composedScale = this.scale + next.scale;
    Provenance composedProvenance =
        (this.provenance == Provenance.STUB || next.provenance == Provenance.STUB)
            ? Provenance.STUB
            : this.provenance;
    return new FxRate(
        composedUnscaled, composedScale, this.from, next.to, this.rateType, composedProvenance);
  }

  /** {@code true} if this rate is a deliberately-fake illustrative stub (not authoritative). */
  public boolean isStub() {
    return provenance == Provenance.STUB;
  }

  /** The rate's value as a human-readable decimal string ({@code unscaled / 10^scale}). */
  public String displayValue() {
    return BigDecimal.valueOf(unscaled, scale).toPlainString();
  }
}
