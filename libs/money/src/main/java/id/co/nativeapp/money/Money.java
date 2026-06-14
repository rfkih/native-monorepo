package id.co.nativeapp.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An immutable monetary value: a {@code long} amount expressed in the <em>minor units</em> of an
 * ISO-4217 {@link Currency} (e.g. cents for USD, whole rupiah for IDR which has zero fraction
 * digits).
 *
 * <p><strong>Money is never a float.</strong> This type exposes no {@code double}/{@code float}
 * constructor or factory; the only way in is via integer minor units or a {@link BigDecimal} major
 * amount whose scale is validated against the currency. Mixing currencies in arithmetic or
 * comparison throws {@link MismatchedCurrencyException} — there is no implicit FX conversion.
 *
 * <p>Instances are value-based: {@link #equals(Object)} and {@link #hashCode()} compare amount and
 * currency. The class is {@code final} and all state is {@code final}, so instances are fully
 * immutable and safely shareable.
 */
public final class Money implements Comparable<Money> {

  /** Amount in the currency's minor units (e.g. cents). Never a float. */
  private final long amountMinor;

  /** The ISO-4217 currency; determines the number of minor units per major. */
  private final Currency currency;

  private Money(long amountMinor, Currency currency) {
    this.amountMinor = amountMinor;
    this.currency = currency;
  }

  // ---------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------

  /**
   * Creates a {@code Money} from an amount already expressed in minor units.
   *
   * @param amountMinor the amount in the currency's minor units
   * @param currencyCode an ISO-4217 currency code (e.g. {@code "USD"})
   * @throws NullPointerException if {@code currencyCode} is null
   * @throws IllegalArgumentException if {@code currencyCode} is not a valid ISO-4217 code
   */
  public static Money ofMinor(long amountMinor, String currencyCode) {
    Objects.requireNonNull(currencyCode, "currencyCode");
    return new Money(amountMinor, Currency.getInstance(currencyCode));
  }

  /**
   * Creates a {@code Money} from an amount already expressed in minor units.
   *
   * @param amountMinor the amount in the currency's minor units
   * @param currency the ISO-4217 currency
   * @throws NullPointerException if {@code currency} is null
   */
  public static Money ofMinor(long amountMinor, Currency currency) {
    Objects.requireNonNull(currency, "currency");
    return new Money(amountMinor, currency);
  }

  /**
   * Creates a {@code Money} from a major-unit {@link BigDecimal} amount (e.g. {@code 12.34} USD),
   * scaling it into minor units by the currency's {@linkplain #fractionDigits(Currency)
   * Intl-aligned fraction digits}.
   *
   * <p>The value is <strong>rejected</strong> if it carries more decimal places than the currency
   * permits: {@code ofMajor("1.50", IDR)} fails because IDR has zero fraction digits, and {@code
   * ofMajor("1.005", USD)} fails because USD has two. This guards against silently dropping
   * sub-unit precision.
   *
   * @param major the amount in major units; must be non-null and exact to the currency's fraction
   *     digits
   * @param currency the ISO-4217 currency
   * @throws NullPointerException if either argument is null
   * @throws IllegalArgumentException if {@code major} has more decimal places than the currency
   *     allows, or the scaled amount overflows a {@code long}
   */
  public static Money ofMajor(BigDecimal major, Currency currency) {
    Objects.requireNonNull(major, "major");
    Objects.requireNonNull(currency, "currency");
    int fractionDigits = fractionDigits(currency);
    if (major.scale() > fractionDigits) {
      throw new IllegalArgumentException(
          "Amount "
              + major.toPlainString()
              + " has more decimal places than "
              + currency.getCurrencyCode()
              + " allows ("
              + fractionDigits
              + " fraction digit(s))");
    }
    // Rescale to exactly the currency's fraction digits (e.g. 1.5 -> 1.50
    // for USD). Any rounding here would mean lost precision, so forbid it.
    BigDecimal scaled = major.movePointRight(fractionDigits);
    try {
      long minor = scaled.longValueExact();
      return new Money(minor, currency);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException(
          "Amount "
              + major.toPlainString()
              + " "
              + currency.getCurrencyCode()
              + " is too large to represent in minor units",
          ex);
    }
  }

  /** The additive identity (zero) in the given currency. */
  public static Money zero(Currency currency) {
    Objects.requireNonNull(currency, "currency");
    return new Money(0L, currency);
  }

  /**
   * The number of minor-unit digits for a currency, aligned with CLDR / the frontend's {@code
   * Intl.NumberFormat} so that {@link #ofMajor} validation and {@link #format} rendering agree with
   * what the React UI shows.
   *
   * <p>This defaults to {@link Currency#getDefaultFractionDigits()} (the ISO-4217 table baked into
   * the JDK), but overrides the handful of currencies where the JDK's ISO value diverges from
   * CLDR/Intl. The most important for this product is <strong>IDR</strong>: the JDK's ISO table
   * reports 2 fraction digits, but CLDR/Intl (and everyday Indonesian usage) treat rupiah as a
   * whole-unit, 0-decimal currency — {@code Rp1.234.567}, not {@code Rp1.234.567,00}. Keeping a
   * tiny, explicit override here means a single source of truth that matches {@code
   * Intl.NumberFormat('id-ID', ...)}.
   */
  static int fractionDigits(Currency currency) {
    // CLDR/Intl overrides where the JDK's ISO-4217 value diverges.
    return switch (currency.getCurrencyCode()) {
      case "IDR" -> 0;
      default -> currency.getDefaultFractionDigits();
    };
  }

  // ---------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------

  /** The amount in the currency's minor units. */
  public long amountMinor() {
    return amountMinor;
  }

  /** The ISO-4217 currency of this amount. */
  public Currency currency() {
    return currency;
  }

  // ---------------------------------------------------------------------
  // Arithmetic
  // ---------------------------------------------------------------------

  /**
   * Returns {@code this + other}.
   *
   * @throws MismatchedCurrencyException if currencies differ
   * @throws ArithmeticException if the result overflows a {@code long}
   */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(this.amountMinor, other.amountMinor), currency);
  }

  /**
   * Returns {@code this - other}.
   *
   * @throws MismatchedCurrencyException if currencies differ
   * @throws ArithmeticException if the result overflows a {@code long}
   */
  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(this.amountMinor, other.amountMinor), currency);
  }

  /**
   * Returns {@code -this}.
   *
   * @throws ArithmeticException if negating overflows a {@code long} (i.e. {@link Long#MIN_VALUE})
   */
  public Money negate() {
    return new Money(Math.negateExact(amountMinor), currency);
  }

  /**
   * Returns {@code this * factor}, multiplying the minor amount by an integer factor (e.g. a
   * quantity).
   *
   * @throws ArithmeticException if the result overflows a {@code long}
   */
  public Money multiply(long factor) {
    return new Money(Math.multiplyExact(amountMinor, factor), currency);
  }

  /**
   * Returns {@code this * numerator / denominator}, rounding the single division to this currency's
   * minor units with {@link java.math.RoundingMode#HALF_EVEN}. This is the ONE rounding point for a
   * proportional calculation (a percentage, a basis-point rate, an allocation share): the
   * multiplication is exact on {@code BigInteger}, and only the final divide rounds — so a chain of
   * proportions never compounds intermediate rounding error.
   *
   * <p>Used by the payroll engine for {@code PERCENT_OF_BASE} earnings, the percentage-with-ceiling
   * social-insurance legs, the progressive-bracket tax steps, and the largest-remainder labor-cost
   * allocation. All amounts stay in integer minor units; there is never a float.
   *
   * @param numerator the proportion numerator (e.g. a basis-point rate, or an attributable amount)
   * @param denominator the proportion denominator (e.g. {@code 10000} for basis points); must be
   *     strictly positive
   * @throws IllegalArgumentException if {@code denominator <= 0}
   */
  public Money mulDiv(long numerator, long denominator) {
    if (denominator <= 0L) {
      throw new IllegalArgumentException("denominator must be strictly positive: " + denominator);
    }
    BigDecimal product = BigDecimal.valueOf(amountMinor).multiply(BigDecimal.valueOf(numerator));
    long minor =
        product
            .divide(BigDecimal.valueOf(denominator), 0, java.math.RoundingMode.HALF_EVEN)
            .longValueExact();
    return new Money(minor, currency);
  }

  /**
   * Returns this amount scaled by a basis-point rate ({@code 1 bp = 1/10000}), e.g. {@code
   * applyBasisPoints(250)} is 2.5% of this amount, rounded once with {@link
   * java.math.RoundingMode#HALF_EVEN} to the currency's minor units. A convenience over {@link
   * #mulDiv(long, long)} with a {@code 10000} denominator.
   *
   * @param basisPoints the rate in basis points (must be non-negative)
   * @throws IllegalArgumentException if {@code basisPoints < 0}
   */
  public Money applyBasisPoints(long basisPoints) {
    if (basisPoints < 0L) {
      throw new IllegalArgumentException("basisPoints must be non-negative: " + basisPoints);
    }
    return mulDiv(basisPoints, 10_000L);
  }

  /**
   * Returns the lesser of {@code this} and {@code other} (same currency). Used to cap a base at a
   * statutory ceiling.
   *
   * @throws MismatchedCurrencyException if currencies differ
   */
  public Money min(Money other) {
    return compareTo(other) <= 0 ? this : other;
  }

  /**
   * Returns the greater of {@code this} and {@code other} (same currency).
   *
   * @throws MismatchedCurrencyException if currencies differ
   */
  public Money max(Money other) {
    return compareTo(other) >= 0 ? this : other;
  }

  /**
   * The number of minor-unit (fraction) digits for this money's currency (e.g. 2 for USD, 0 for
   * IDR), aligned with CLDR/Intl. Exposed so the payroll engine can reason about rounding scale.
   */
  public int fractionDigits() {
    return fractionDigits(currency);
  }

  /**
   * Asserts {@code other} is in the same currency as {@code this}, throwing {@link
   * MismatchedCurrencyException} otherwise. Exposed so the payroll engine can fail a run at the
   * point it discovers a comp-package / assignment in a currency other than the run's base currency
   * (there is no implicit FX — finance owns FX).
   *
   * @throws MismatchedCurrencyException if currencies differ
   */
  public void requireSameCurrencyAs(Money other) {
    requireSameCurrency(other);
  }

  // ---------------------------------------------------------------------
  // Predicates & comparison
  // ---------------------------------------------------------------------

  /** {@code true} if this amount is exactly zero. */
  public boolean isZero() {
    return amountMinor == 0L;
  }

  /** {@code true} if this amount is strictly greater than zero. */
  public boolean isPositive() {
    return amountMinor > 0L;
  }

  /** {@code true} if this amount is strictly less than zero. */
  public boolean isNegative() {
    return amountMinor < 0L;
  }

  /**
   * Orders two amounts of the <em>same</em> currency.
   *
   * @throws MismatchedCurrencyException if currencies differ — different currencies have no natural
   *     order
   */
  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return Long.compare(this.amountMinor, other.amountMinor);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!this.currency.equals(other.currency)) {
      throw new MismatchedCurrencyException(this.currency, other.currency);
    }
  }

  // ---------------------------------------------------------------------
  // Formatting
  // ---------------------------------------------------------------------

  /**
   * Formats this amount as a locale-aware currency string for <em>this</em> currency (the locale
   * governs digit grouping, decimal marks and symbol placement, while the currency itself is always
   * this money's currency).
   *
   * <p>This mirrors what {@code Intl.NumberFormat(locale, {style:'currency', currency})} produces
   * on the frontend, keeping server- and client-rendered money consistent.
   *
   * @param locale the formatting locale (e.g. {@code Locale.US}, {@code Locale.of("id", "ID")})
   * @return e.g. {@code "$1,234.50"} for USD/en-US, or {@code "Rp1.234"} for IDR/id-ID
   */
  public String format(Locale locale) {
    Objects.requireNonNull(locale, "locale");
    NumberFormat fmt = NumberFormat.getCurrencyInstance(locale);
    fmt.setCurrency(currency);
    // setCurrency() does not realign the fraction digits to this currency
    // (the locale's default currency may have a different precision, e.g.
    // an id-ID NumberFormat defaults to 2 digits even for IDR). Pin them to
    // the currency's own fraction digits so the output matches what
    // Intl.NumberFormat renders for the same currency.
    int fractionDigits = fractionDigits(currency);
    fmt.setMinimumFractionDigits(fractionDigits);
    fmt.setMaximumFractionDigits(fractionDigits);
    return fmt.format(toMajorBigDecimal());
  }

  /**
   * This amount as a {@link BigDecimal} in major units, scaled to the currency's fraction digits
   * (e.g. {@code 123450} minor USD -> {@code 1234.50}).
   */
  private BigDecimal toMajorBigDecimal() {
    return BigDecimal.valueOf(amountMinor, fractionDigits(currency));
  }

  // ---------------------------------------------------------------------
  // Identity
  // ---------------------------------------------------------------------

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Money other)) {
      return false;
    }
    return this.amountMinor == other.amountMinor && this.currency.equals(other.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(amountMinor, currency);
  }

  /**
   * A readable, machine-parseable representation: minor amount and currency code, e.g. {@code
   * "123450 USD"}. Not localized — use {@link #format(Locale)} for user-facing output.
   */
  @Override
  public String toString() {
    return amountMinor + " " + currency.getCurrencyCode();
  }
}
