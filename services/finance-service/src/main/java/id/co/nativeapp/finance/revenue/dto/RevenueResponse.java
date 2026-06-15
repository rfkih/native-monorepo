package id.co.nativeapp.finance.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import id.co.nativeapp.finance.fx.dto.AppliedRate;
import id.co.nativeapp.finance.fx.dto.RevenuePresentation;
import id.co.nativeapp.money.Money;
import java.util.List;

/**
 * Consolidated-revenue query response for one period (the bound tenant).
 *
 * <p>The NATIVE total is carried as the rule-8 pair — integer {@code totalMinor} (minor units) +
 * ISO-4217 {@code currency} — never a float. The frontend formats it locale-aware via {@code
 * Intl.NumberFormat} reading the company base currency (no server-side localized string; HR-9). A
 * period with no postings yet returns a zero total in the requested/base currency.
 *
 * <p><strong>Presentation fields are ADDITIVE and OPTIONAL</strong> (all nullable, {@code
 * JsonInclude.NON_NULL} so old clients ignore them and a no-{@code presentation} request serializes
 * exactly as before). When a {@code presentation} currency is requested they carry the VIEW-ONLY
 * converted figure: the native total is STILL returned alongside — presentation is purely additive
 * context, never a replacement, and never a base-currency change.
 *
 * @param period the accounting period {@code YYYY-MM}
 * @param totalMinor the NATIVE (transaction/base currency) total in minor units
 * @param currency the native ISO-4217 currency code
 * @param presentationCurrency the requested presentation ISO-4217 code (echoed), or null
 * @param presentationTotalMinor the converted total in the presentation currency's minor units, or
 *     null
 * @param usesStubFx {@code true} if any FX rate used was a STUB (the dashboard badges the figure
 *     provisional via an i18n key — never a hardcoded string, rule 9); null when not converted
 * @param fxAsOf the as-of date the rate was resolved at ({@code YYYY-MM-DD}), or null
 * @param appliedRates the auditable rate(s) used, or null when not converted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevenueResponse(
    String period,
    long totalMinor,
    String currency,
    String presentationCurrency,
    Long presentationTotalMinor,
    Boolean usesStubFx,
    String fxAsOf,
    List<AppliedRate> appliedRates) {

  /** Builds the native-only response from a {@link Money} total (no presentation conversion). */
  public static RevenueResponse of(String period, Money total) {
    return new RevenueResponse(
        period,
        total.amountMinor(),
        total.currency().getCurrencyCode(),
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Builds the response carrying BOTH the native total and the additive presentation view (the
   * native total is never dropped).
   */
  public static RevenueResponse withPresentation(
      String period, Money nativeTotal, RevenuePresentation presentation) {
    return new RevenueResponse(
        period,
        nativeTotal.amountMinor(),
        nativeTotal.currency().getCurrencyCode(),
        presentation.presentationTotal().currency().getCurrencyCode(),
        presentation.presentationTotal().amountMinor(),
        presentation.usesStubFx(),
        presentation.fxAsOf().toString(),
        presentation.appliedRates());
  }
}
