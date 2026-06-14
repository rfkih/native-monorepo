package id.co.nativeapp.finance.pnl;

import com.fasterxml.jackson.annotation.JsonInclude;
import id.co.nativeapp.finance.fx.AppliedRate;
import id.co.nativeapp.finance.fx.PnlPresentation;
import java.util.List;

/**
 * Consolidated P&amp;L query response for one period (the bound tenant).
 *
 * <p>The NATIVE money is carried as the rule-8 pair — integer minor units + ISO-4217 {@code
 * currency} — never a float, for each of revenue, expense, and the derived net. The frontend
 * formats each locale-aware via {@code Intl.NumberFormat} reading the company base currency (no
 * server-side localized string; HR-9). A period with no postings yet returns zero revenue / expense
 * / net in the requested/base currency.
 *
 * <p><strong>Presentation fields are ADDITIVE and OPTIONAL</strong> (all nullable, {@code
 * JsonInclude.NON_NULL} so old clients ignore them and a no-{@code presentation} request serializes
 * exactly as before). When a {@code presentation} currency is requested they carry the VIEW-ONLY
 * converted P&amp;L (each leg at the period AVERAGE rate, net derived in the presentation
 * currency): the native figures are STILL returned alongside.
 *
 * @param period the accounting period {@code YYYY-MM}
 * @param revenueMinor NATIVE accumulated revenue in minor units
 * @param expenseMinor NATIVE accumulated expense in minor units
 * @param netMinor NATIVE revenue − expense in minor units (derived, never stored)
 * @param currency the native ISO-4217 currency code
 * @param usesIllustrativeRules whether this period's figure is contaminated by illustrative-
 *     placeholder labor data (#23) — distinct from {@code usesStubFx}; the dashboard keys an i18n
 *     badge off it (rule 9)
 * @param presentationCurrency the requested presentation ISO-4217 code (echoed), or null
 * @param presentationRevenueMinor converted revenue in the presentation currency's minor units, or
 *     null
 * @param presentationExpenseMinor converted expense in the presentation currency's minor units, or
 *     null
 * @param presentationNetMinor converted net (revenue − expense in presentation currency), or null
 * @param usesStubFx {@code true} if ANY FX rate used was a STUB (the dashboard badges the
 *     presentation figure provisional via an i18n key — rule 9); null when not converted
 * @param fxAsOf the as-of date the rates were resolved at ({@code YYYY-MM-DD}), or null
 * @param appliedRates the auditable rate(s) used, or null when not converted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PnlResponse(
    String period,
    long revenueMinor,
    long expenseMinor,
    long netMinor,
    String currency,
    boolean usesIllustrativeRules,
    String presentationCurrency,
    Long presentationRevenueMinor,
    Long presentationExpenseMinor,
    Long presentationNetMinor,
    Boolean usesStubFx,
    String fxAsOf,
    List<AppliedRate> appliedRates) {

  /** Builds the native-only response from a {@link ConsolidatedPnl} accumulator row. */
  static PnlResponse from(ConsolidatedPnl pnl) {
    return new PnlResponse(
        pnl.getPeriod(),
        pnl.revenue().amountMinor(),
        pnl.expense().amountMinor(),
        pnl.net().amountMinor(),
        pnl.revenue().currency().getCurrencyCode(),
        pnl.usesIllustrativeRules(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Builds the response carrying BOTH the native P&amp;L and the additive presentation view (the
   * native figures are never dropped).
   */
  static PnlResponse withPresentation(ConsolidatedPnl pnl, PnlPresentation presentation) {
    return new PnlResponse(
        pnl.getPeriod(),
        pnl.revenue().amountMinor(),
        pnl.expense().amountMinor(),
        pnl.net().amountMinor(),
        pnl.revenue().currency().getCurrencyCode(),
        pnl.usesIllustrativeRules(),
        presentation.presentationRevenue().currency().getCurrencyCode(),
        presentation.presentationRevenue().amountMinor(),
        presentation.presentationExpense().amountMinor(),
        presentation.presentationNet().amountMinor(),
        presentation.usesStubFx(),
        presentation.fxAsOf().toString(),
        presentation.appliedRates());
  }

  /**
   * A zero P&amp;L in {@code currency} for a period with no postings yet — never illustrative (no
   * labor has landed).
   */
  static PnlResponse zero(String period, String currency) {
    return new PnlResponse(
        period, 0L, 0L, 0L, currency, false, null, null, null, null, null, null, null);
  }
}
