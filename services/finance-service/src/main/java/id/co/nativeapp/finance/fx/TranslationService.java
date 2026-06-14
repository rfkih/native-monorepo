package id.co.nativeapp.finance.fx;

import id.co.nativeapp.money.FxRate;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.money.Provenance;
import id.co.nativeapp.money.RateType;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Selects an FX rate by {@link RateType} + as-of date and applies it via {@link FxRate#convert},
 * the IAS-21-shaped translation core P3c delivers and P3d (cross-currency GROUP consolidation) will
 * consume.
 *
 * <ul>
 *   <li><strong>{@link #translateFlow}</strong> — translates a FLOW (revenue, expense, P&amp;L net,
 *       consolidated revenue) at the <strong>AVERAGE</strong> rate for the period. The as-of date
 *       is the period itself (the average row's effective window contains it).
 *   <li><strong>{@link #translateBalance}</strong> — translates a BALANCE (balance-sheet / position
 *       item) at the <strong>CLOSING</strong> rate at the period-END date. Defined now for P3d's
 *       trial-balance translation; the period-end as-of is the last day of the YYYY-MM period.
 * </ul>
 *
 * <p><strong>Resolution</strong> delegates to the {@link FxRateProvider} SPI (the {@code fx_rate}
 * table today, a live provider later), effective-dated + highest-version-wins — identical semantics
 * to {@code GlAccountResolver}. A same-currency request short-circuits to the identity rate (no DB
 * round-trip).
 *
 * <p><strong>Triangulation.</strong> If no DIRECT {@code (from, to)} rate exists, it attempts a
 * single-hop pivot via {@link #PIVOT} (USD): resolve {@code from->USD} and {@code USD->to} at the
 * same rate type + as-of, COMPOSE the two integer ratios exactly ({@link FxRate#composeWith}), and
 * round ONCE at the target — no intermediate-currency rounding. The {@code usesStubFx} flag is the
 * OR of both legs' provenance.
 *
 * <p><strong>Fail-loud.</strong> If neither a direct nor a USD-pivot pair resolves, it throws
 * {@link MissingFxRateException} (→ RFC-7807 422). It NEVER invents a rate or silently returns the
 * native figure mislabelled as converted. A conversion is ATOMIC: a missing leg fails the whole
 * call.
 */
@Service
public class TranslationService {

  /** The single pivot currency for triangulation in P3c (USD-only, single-hop). */
  private static final Currency PIVOT = Currency.getInstance("USD");

  private final FxRateProvider rateProvider;

  public TranslationService(FxRateProvider rateProvider) {
    this.rateProvider = Objects.requireNonNull(rateProvider, "rateProvider");
  }

  /**
   * Translates a FLOW (e.g. period revenue / expense) into {@code target} at the AVERAGE rate for
   * {@code period}.
   *
   * @param flow the amount to translate (in its stored transaction/base currency)
   * @param period the accounting period {@code YYYY-MM}
   * @param target the presentation currency
   * @return the converted amount + stub flag + applied rate(s) + as-of date
   * @throws MissingFxRateException if no AVERAGE rate (direct or USD-pivot) resolves for the period
   */
  public Translated translateFlow(Money flow, String period, Currency target) {
    Objects.requireNonNull(period, "period");
    // A flow translates at AVERAGE; the as-of is a representative day in the period (the average
    // row's effective window spans it). Use the first day of the month — DATE granularity, UTC,
    // matching the effective-dating granularity of fx_rate.
    LocalDate asOf = YearMonth.parse(period).atDay(1);
    return translate(flow, target, RateType.AVERAGE, asOf);
  }

  /**
   * Translates a BALANCE (balance-sheet / position item) into {@code target} at the CLOSING rate at
   * the period-END date (the last day of {@code period}). Provided now for P3d's trial-balance
   * translation.
   *
   * @param balance the closing balance to translate (in its stored currency)
   * @param period the accounting period {@code YYYY-MM} whose period-end CLOSING rate applies
   * @param target the presentation currency
   * @return the converted amount + stub flag + applied rate(s) + as-of date
   * @throws MissingFxRateException if no CLOSING rate (direct or USD-pivot) resolves at period-end
   */
  public Translated translateBalance(Money balance, String period, Currency target) {
    Objects.requireNonNull(period, "period");
    LocalDate asOf = YearMonth.parse(period).atEndOfMonth();
    return translate(balance, target, RateType.CLOSING, asOf);
  }

  /**
   * The shared translate: identity short-circuit, then direct, then USD-pivot triangulation, else
   * fail loud.
   */
  private Translated translate(Money source, Currency target, RateType rateType, LocalDate asOf) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    Currency from = source.currency();

    // Same currency: identity rate, no DB round-trip, never stub-contaminated by the rate itself.
    if (from.equals(target)) {
      FxRate identity = FxRate.identity(target, rateType, Provenance.OFFICIAL);
      return new Translated(
          identity.convert(source, target),
          false,
          asOf,
          List.of(AppliedRate.direct(identity, null)));
    }

    // Direct pair.
    Optional<ResolvedFxRate> direct = rateProvider.rateFor(from, target, rateType, asOf);
    if (direct.isPresent()) {
      ResolvedFxRate resolved = direct.get();
      FxRate rate = resolved.rate();
      Money converted = rate.convert(source, target);
      return new Translated(
          converted,
          rate.isStub(),
          asOf,
          List.of(AppliedRate.direct(rate, resolved.effectiveFrom().toString())));
    }

    // USD-pivot triangulation (single hop). Only attempt if neither side is already USD (a missing
    // direct from->USD or USD->to is genuinely missing, not pivotable through itself).
    if (!from.equals(PIVOT) && !target.equals(PIVOT)) {
      Optional<ResolvedFxRate> leg1 = rateProvider.rateFor(from, PIVOT, rateType, asOf);
      Optional<ResolvedFxRate> leg2 = rateProvider.rateFor(PIVOT, target, rateType, asOf);
      if (leg1.isPresent() && leg2.isPresent()) {
        FxRate composed = leg1.get().rate().composeWith(leg2.get().rate());
        Money converted = composed.convert(source, target);
        List<AppliedRate> applied = new ArrayList<>(2);
        applied.add(
            AppliedRate.leg(
                leg1.get().rate(), leg1.get().effectiveFrom().toString(), PIVOT.getCurrencyCode()));
        applied.add(
            AppliedRate.leg(
                leg2.get().rate(), leg2.get().effectiveFrom().toString(), PIVOT.getCurrencyCode()));
        return new Translated(converted, composed.isStub(), asOf, applied);
      }
    }

    // No direct and no pivot — fail loud (atomic; a half-converted figure is never returned).
    throw new MissingFxRateException(from, target, asOf);
  }
}
