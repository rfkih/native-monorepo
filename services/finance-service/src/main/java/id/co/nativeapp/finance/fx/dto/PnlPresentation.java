package id.co.nativeapp.finance.fx.dto;

import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The read-time presentation-currency view of a consolidated P&amp;L figure: each leg (revenue,
 * expense, and the derived net) converted into the presentation currency, the OR-ed stub flag, the
 * as-of date, and the auditable applied rate(s). Purely additive context computed on the way out —
 * the stored {@code consolidated_pnl} row is never touched.
 *
 * <p>Per IAS-21, each FLOW leg converts at the AVERAGE rate; {@code net = revenue - expense} is
 * then computed IN the presentation currency (each leg rounded once, then subtracted — never the
 * native net re-rounded), so the presentation net is internally consistent with its presentation
 * legs.
 *
 * @param presentationRevenue revenue converted into the presentation currency
 * @param presentationExpense expense converted into the presentation currency
 * @param presentationNet revenue − expense in the presentation currency
 * @param usesStubFx {@code true} if ANY applied rate (across revenue + expense legs) was a STUB
 * @param fxAsOf the as-of date the AVERAGE rates were resolved at (the period)
 * @param appliedRates the auditable rate(s) used
 */
public record PnlPresentation(
    Money presentationRevenue,
    Money presentationExpense,
    Money presentationNet,
    boolean usesStubFx,
    LocalDate fxAsOf,
    List<AppliedRate> appliedRates) {

  public PnlPresentation {
    Objects.requireNonNull(presentationRevenue, "presentationRevenue");
    Objects.requireNonNull(presentationExpense, "presentationExpense");
    Objects.requireNonNull(presentationNet, "presentationNet");
    Objects.requireNonNull(fxAsOf, "fxAsOf");
    appliedRates = List.copyOf(appliedRates);
  }
}
