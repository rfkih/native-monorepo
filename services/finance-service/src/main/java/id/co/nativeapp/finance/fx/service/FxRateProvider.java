package id.co.nativeapp.finance.fx.service;

import id.co.nativeapp.finance.fx.domain.ResolvedFxRate;
import id.co.nativeapp.money.RateType;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

/**
 * The FX-rate lookup SPI — the swappable seam between the conversion machinery and the rate SOURCE.
 *
 * <p>The default implementation, {@link TableFxRateProvider}, reads the global {@code fx_rate}
 * reference table (the seeded STUB rates today). A future {@code LiveFxRateProvider} (Bank
 * Indonesia / ECB, etc.) would insert new effective-dated rows with {@code provenance = LIVE /
 * OFFICIAL} into the SAME table/shape, or feed them another way — conversions then resolve to the
 * live row and {@code uses_stub_fx} becomes {@code false} automatically. No consumer/read-path code
 * changes: the stub→live cutover is purely DATA + config (which provider bean is active).
 *
 * <p>Resolution is EFFECTIVE-DATED: a rate is selected by its {@code (from, to, rateType)} key and
 * the {@code [effective_from, effective_to]} window containing {@code asOf}, highest version wins —
 * identical semantics to {@code mapping_rule}. An {@link Optional#empty()} means no DIRECT pair was
 * found; triangulation and fail-loud handling live in {@link TranslationService}, not here, so an
 * implementation only answers the single-pair question.
 *
 * <p>The result is a {@link ResolvedFxRate} — the pure {@code FxRate} value plus the {@code
 * effective_from} of the row it resolved to (so the read layer can surface the auditable {@code
 * appliedRates[]}). The {@code FxRate} value type itself stays free of effective-dating, reusable
 * by P3d.
 */
public interface FxRateProvider {

  /**
   * Resolves the rate for a {@code (from, to, rateType)} pair effective at {@code asOf}, or empty
   * if no such direct rate exists. A same-currency request ({@code from.equals(to)}) is the
   * caller's concern (the translation layer short-circuits it to the identity rate); an
   * implementation need not special-case it.
   *
   * @param from the source currency
   * @param to the target currency
   * @param rateType the IAS-21 rate kind (CLOSING / AVERAGE / SPOT)
   * @param asOf the as-of date the effective window must contain
   * @return the resolved rate + its effective-from, or empty if no direct pair is effective at
   *     {@code asOf}
   */
  Optional<ResolvedFxRate> rateFor(Currency from, Currency to, RateType rateType, LocalDate asOf);
}
