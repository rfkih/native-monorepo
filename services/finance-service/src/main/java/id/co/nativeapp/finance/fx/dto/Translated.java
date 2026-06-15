package id.co.nativeapp.finance.fx.dto;

import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of translating one {@link Money} leg into a presentation currency: the converted
 * amount, whether ANY applied rate was a STUB (the sticky {@code uses_stub_fx} contamination flag),
 * the as-of date the rates were resolved at, and the auditable list of {@link AppliedRate}s used
 * (one for a direct conversion, two for a USD-pivot triangulation).
 *
 * <p>The {@code usesStubFx} flag is OR-ed across every leg, so a triangulated conversion is
 * stub-contaminated if EITHER leg is a stub — exactly the {@code uses_illustrative_rules}
 * discipline the payroll path uses. The presentation layer ORs this across the P&amp;L legs
 * (revenue, expense) so the whole figure is flagged provisional if any rate is a stub.
 *
 * @param amount the converted amount as {@link Money} in the presentation currency
 * @param usesStubFx {@code true} if any applied rate had {@code provenance == STUB}
 * @param asOf the as-of date the rates were resolved at (period-end for CLOSING, period for
 *     AVERAGE)
 * @param appliedRates the auditable rate(s) applied — never empty
 */
public record Translated(
    Money amount, boolean usesStubFx, LocalDate asOf, List<AppliedRate> appliedRates) {

  /** Defensive copy of the applied-rate list keeps the record immutable. */
  public Translated {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(asOf, "asOf");
    appliedRates = List.copyOf(appliedRates);
  }
}
