package id.co.nativeapp.finance.unitpnl.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/pnl/org-units/{orgUnitId}} — the per-org-unit P&amp;L
 * rollup: unit totals (rule-8 Money pair: minor units + ISO-4217) plus a per-outlet breakdown
 * ({@code outlets} is empty when the unit is itself an outlet). {@code netMinor} is derived
 * (revenue − expense), never stored. {@code currency} may be {@code null} inside the service layer
 * for a known unit with zero postings; the controller either substitutes the caller's currency
 * hint or returns 204 (mirroring {@code PnlController}) — a {@code null} currency never reaches
 * the wire.
 *
 * @param orgUnitId the unit the rollup is for
 * @param period the accounting period {@code YYYY-MM}
 * @param revenueMinor signed revenue total in minor units (reversals netted)
 * @param expenseMinor signed expense total in minor units
 * @param netMinor revenue − expense
 * @param currency ISO-4217 code of the postings (single base currency, M1.5)
 * @param usesIllustrativeRules whether any rolled-up posting came from an illustrative rule
 * @param outlets per-child-outlet breakdown, revenue-descending; empty for an outlet-type unit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnitPnlResponse(
    UUID orgUnitId,
    String period,
    long revenueMinor,
    long expenseMinor,
    long netMinor,
    String currency,
    boolean usesIllustrativeRules,
    List<OutletPnlRow> outlets) {

  /** Returns a copy with {@code currency} replaced (the controller's zero-P&amp;L hint path). */
  public UnitPnlResponse withCurrency(String newCurrency) {
    return new UnitPnlResponse(
        orgUnitId,
        period,
        revenueMinor,
        expenseMinor,
        netMinor,
        newCurrency,
        usesIllustrativeRules,
        outlets);
  }

  /**
   * One child outlet's slice of the rollup.
   *
   * @param orgUnitId the outlet's org-unit id
   * @param name the outlet display name (from the cached org tree)
   * @param active whether the outlet is currently active
   * @param revenueMinor signed revenue in minor units
   * @param expenseMinor signed expense in minor units
   * @param netMinor revenue − expense
   */
  public record OutletPnlRow(
      UUID orgUnitId,
      String name,
      boolean active,
      long revenueMinor,
      long expenseMinor,
      long netMinor) {}
}
