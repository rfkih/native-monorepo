package id.co.nativeapp.finance.fx.dto;

import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The read-time presentation-currency view of a consolidated-revenue figure: the converted total,
 * the stub-contamination flag, the as-of date, and the auditable applied rate(s). Purely additive
 * context computed on the way out — the stored {@code consolidated_revenue} row is never touched.
 *
 * @param presentationTotal the native total converted into the presentation currency
 * @param usesStubFx {@code true} if any applied rate was a STUB (the dashboard badges it
 *     provisional)
 * @param fxAsOf the as-of date the AVERAGE rate was resolved at (the period)
 * @param appliedRates the auditable rate(s) used (one direct, or two for a USD-pivot)
 */
public record RevenuePresentation(
    Money presentationTotal, boolean usesStubFx, LocalDate fxAsOf, List<AppliedRate> appliedRates) {

  public RevenuePresentation {
    Objects.requireNonNull(presentationTotal, "presentationTotal");
    Objects.requireNonNull(fxAsOf, "fxAsOf");
    appliedRates = List.copyOf(appliedRates);
  }
}
