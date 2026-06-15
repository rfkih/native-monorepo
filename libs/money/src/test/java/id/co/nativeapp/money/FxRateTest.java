package id.co.nativeapp.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * Exact, reproducible FX conversion to {@link Money} minor units -- including the cross-fraction-
 * digit (USD 2dp &lt;-&gt; IDR 0dp) rounding boundaries, identity short-circuit,
 * mismatched-currency refusal, and the compose-then-round-once triangulation. No float anywhere:
 * the rate is a scaled-integer pair, BigDecimal is transient only.
 */
class FxRateTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency IDR = Currency.getInstance("IDR");
  private static final Currency EUR = Currency.getInstance("EUR");

  // ------------------------------------------------------------------
  // USD -> IDR: target has 0 fraction digits, source has 2.
  // ------------------------------------------------------------------

  @Test
  void usdToIdrRoundsToWholeRupiah() {
    // 1 USD = 16,000 IDR (unscaled=16000, scale=0). 100 USD (10_000 minor) -> 1,600,000 IDR.
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    Money converted = rate.convert(Money.ofMinor(10_000L, USD), IDR);
    assertEquals(Money.ofMinor(1_600_000L, IDR), converted);
    assertEquals(IDR, converted.currency());
  }

  @Test
  void usdToIdrWithAFractionalRateRoundsHalfEvenToWholeRupiah() {
    // 1 USD = 16,250.5 IDR (unscaled=162505, scale=1). 1 USD (100 minor) -> 16,250.5 -> 16,250
    // (HALF_EVEN: .5 ties to the even neighbour 16,250).
    FxRate rate = new FxRate(162_505L, 1, USD, IDR, RateType.CLOSING, Provenance.STUB);
    assertEquals(Money.ofMinor(16_250L, IDR), rate.convert(Money.ofMinor(100L, USD), IDR));

    // 1 USD = 16,251.5 IDR -> 16,251.5 -> 16,252 (HALF_EVEN: ties to even 16,252).
    FxRate rate2 = new FxRate(162_515L, 1, USD, IDR, RateType.CLOSING, Provenance.STUB);
    assertEquals(Money.ofMinor(16_252L, IDR), rate2.convert(Money.ofMinor(100L, USD), IDR));
  }

  // ------------------------------------------------------------------
  // IDR -> USD: target has 2 fraction digits, source has 0.
  // ------------------------------------------------------------------

  @Test
  void idrToUsdRoundsToCents() {
    // 1 IDR = 0.0000625 USD (unscaled=625, scale=7, since 625e-7 = 6.25e-5). 16,000 IDR -> 1.00
    // USD (100 minor) -- the exact inverse of 1 USD = 16,000 IDR.
    FxRate rate = new FxRate(625L, 7, IDR, USD, RateType.CLOSING, Provenance.STUB);
    Money converted = rate.convert(Money.ofMinor(16_000L, IDR), USD);
    assertEquals(Money.ofMinor(100L, USD), converted);
  }

  @Test
  void idrToUsdRoundsHalfEvenAtTheCent() {
    // 1 IDR = 0.0000625 USD (unscaled=625, scale=7).
    FxRate rate = new FxRate(625L, 7, IDR, USD, RateType.CLOSING, Provenance.STUB);
    // 80 IDR -> 0.005 USD = 0.5 cents -> 0 (HALF_EVEN ties to even 0).
    assertEquals(Money.ofMinor(0L, USD), rate.convert(Money.ofMinor(80L, IDR), USD));
    // 240 IDR -> 0.015 USD = 1.5 cents -> 2 (HALF_EVEN ties to even 2).
    assertEquals(Money.ofMinor(2L, USD), rate.convert(Money.ofMinor(240L, IDR), USD));
    // 400 IDR -> 0.025 USD = 2.5 cents -> 2 (HALF_EVEN ties to even 2).
    assertEquals(Money.ofMinor(2L, USD), rate.convert(Money.ofMinor(400L, IDR), USD));
  }

  // ------------------------------------------------------------------
  // Reproducibility & exactness
  // ------------------------------------------------------------------

  @Test
  void conversionIsReproducibleForTheSameInputs() {
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.AVERAGE, Provenance.STUB);
    Money source = Money.ofMinor(123_456L, USD);
    assertEquals(rate.convert(source, IDR), rate.convert(source, IDR));
  }

  @Test
  void zeroConvertsToZeroInTheTargetCurrency() {
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.LIVE);
    assertEquals(Money.zero(IDR), rate.convert(Money.zero(USD), IDR));
  }

  @Test
  void negativeAmountConvertsWithSignPreserved() {
    // A REVERSAL contra leg can be negative; FX must preserve sign and round HALF_EVEN toward even.
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    assertEquals(Money.ofMinor(-1_600_000L, IDR), rate.convert(Money.ofMinor(-10_000L, USD), IDR));
  }

  // ------------------------------------------------------------------
  // Identity
  // ------------------------------------------------------------------

  @Test
  void identityRateReturnsTheInputUnchanged() {
    FxRate id = FxRate.identity(USD, RateType.SPOT, Provenance.OFFICIAL);
    Money usd = Money.ofMinor(12_345L, USD);
    assertEquals(usd, id.convert(usd, USD));
    assertEquals(1L, id.unscaled());
    assertEquals(0, id.scale());
    assertFalse(id.isStub());
  }

  // ------------------------------------------------------------------
  // No implicit FX / guard rails
  // ------------------------------------------------------------------

  @Test
  void convertRejectsASourceInTheWrongCurrency() {
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    MismatchedCurrencyException ex =
        assertThrows(
            MismatchedCurrencyException.class, () -> rate.convert(Money.ofMinor(1L, EUR), IDR));
    assertEquals(USD, ex.left());
    assertEquals(EUR, ex.right());
  }

  @Test
  void convertRejectsAWrongTargetCurrency() {
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    assertThrows(IllegalArgumentException.class, () -> rate.convert(Money.ofMinor(100L, USD), EUR));
  }

  @Test
  void constructorRejectsNonPositiveUnscaledAndNegativeScale() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxRate(0L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxRate(-1L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxRate(1L, -1, USD, IDR, RateType.CLOSING, Provenance.STUB));
  }

  @Test
  void constructorRejectsAScaleAboveTheDbEnforcedUpperBound() {
    // The DB enforces fx_rate.rate_scale BETWEEN 0 AND 12 (finance V4); the value type must be
    // self-protecting regardless of storage, so a scale beyond MAX_SCALE is rejected loudly.
    assertEquals(12, FxRate.MAX_SCALE);
    // The exact upper bound is accepted...
    new FxRate(1L, FxRate.MAX_SCALE, USD, IDR, RateType.CLOSING, Provenance.STUB);
    // ...one past it is not.
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxRate(1L, FxRate.MAX_SCALE + 1, USD, IDR, RateType.CLOSING, Provenance.STUB));
  }

  // ------------------------------------------------------------------
  // Triangulation: compose-then-round-once, OR-ed provenance
  // ------------------------------------------------------------------

  @Test
  void composeMultipliesRatiosAndRoundsOnceAtTheTarget() {
    // EUR -> USD (1.1, unscaled=11 scale=1) then USD -> IDR (16,000). Composed EUR -> IDR =
    // 1.1 * 16,000 = 17,600 (unscaled=11*16000=176000, scale=1). 1 EUR (100 minor) ->
    // 17,600 IDR.
    FxRate eurUsd = new FxRate(11L, 1, EUR, USD, RateType.CLOSING, Provenance.LIVE);
    FxRate usdIdr = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.LIVE);
    FxRate eurIdr = eurUsd.composeWith(usdIdr);

    assertEquals(176_000L, eurIdr.unscaled());
    assertEquals(1, eurIdr.scale());
    assertEquals(EUR, eurIdr.from());
    assertEquals(IDR, eurIdr.to());
    assertEquals(Money.ofMinor(17_600L, IDR), eurIdr.convert(Money.ofMinor(100L, EUR), IDR));
  }

  @Test
  void composeOrsStubProvenanceAcrossLegs() {
    FxRate liveLeg = new FxRate(11L, 1, EUR, USD, RateType.CLOSING, Provenance.LIVE);
    FxRate stubLeg = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    // Either leg STUB -> composed is STUB (stub-contaminated).
    assertTrue(liveLeg.composeWith(stubLeg).isStub());
    // Both non-stub -> not stub.
    FxRate liveLeg2 = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.OFFICIAL);
    assertFalse(liveLeg.composeWith(liveLeg2).isStub());
  }

  @Test
  void composeRejectsLegsThatDoNotMeetAtAPivot() {
    FxRate eurUsd = new FxRate(11L, 1, EUR, USD, RateType.CLOSING, Provenance.LIVE);
    FxRate idrUsd = new FxRate(625L, 10, IDR, USD, RateType.CLOSING, Provenance.LIVE);
    // EUR->USD then IDR->USD do not chain (USD != IDR).
    assertThrows(IllegalArgumentException.class, () -> eurUsd.composeWith(idrUsd));
  }

  // ------------------------------------------------------------------
  // No silent wrap: the long arithmetic fails LOUD (ArithmeticException) near Long.MAX_VALUE.
  // ------------------------------------------------------------------

  @Test
  void convertFailsLoudRatherThanSilentlyWrappingOnAHugeSourceAmount() {
    // A huge USD source at a >1 rate overflows a long minor result. The numerator multiply is exact
    // (BigDecimal), but longValueExact() at the final minor-unit result must THROW rather than
    // wrap.
    //   result ≈ source_minor * 16_000 * 10^0 / (10^0 * 10^2) = source_minor * 160, which for a
    //   near-Long.MAX_VALUE source is far outside long range.
    FxRate rate = new FxRate(16_000L, 0, USD, IDR, RateType.CLOSING, Provenance.STUB);
    Money huge = Money.ofMinor(Long.MAX_VALUE, USD);
    assertThrows(ArithmeticException.class, () -> rate.convert(huge, IDR));
  }

  @Test
  void composeWithFailsLoudRatherThanSilentlyWrappingWhenTheProductOverflows() {
    // Two legs whose unscaled product exceeds Long.MAX_VALUE: 5e9 * 5e9 = 2.5e19 > 9.22e18. The
    // composed scale (0 + 0) stays within MAX_SCALE, so the guard we hit is multiplyExact — proving
    // the composed-rate path fails LOUD (ArithmeticException) rather than silently wrapping
    // unscaled.
    FxRate eurUsd = new FxRate(5_000_000_000L, 0, EUR, USD, RateType.CLOSING, Provenance.LIVE);
    FxRate usdIdr = new FxRate(5_000_000_000L, 0, USD, IDR, RateType.CLOSING, Provenance.LIVE);
    assertThrows(ArithmeticException.class, () -> eurUsd.composeWith(usdIdr));
  }

  // ------------------------------------------------------------------
  // No-float contract (reflection guard): no float/double/BigDecimal FIELD on the value type.
  // ------------------------------------------------------------------

  @Test
  void holdsNoFloatingPointOrBigDecimalField() {
    for (Field f : FxRate.class.getDeclaredFields()) {
      Class<?> t = f.getType();
      assertFalse(t == float.class, "FxRate must hold no float field");
      assertFalse(t == double.class, "FxRate must hold no double field");
      assertFalse(t == Float.class, "FxRate must hold no Float field");
      assertFalse(t == Double.class, "FxRate must hold no Double field");
      assertFalse(t == java.math.BigDecimal.class, "FxRate must hold no BigDecimal field");
    }
  }
}
