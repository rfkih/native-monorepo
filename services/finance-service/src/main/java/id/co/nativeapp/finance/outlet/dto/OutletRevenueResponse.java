package id.co.nativeapp.finance.outlet.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/pnl/outlets?period=YYYY-MM}.
 *
 * <p>Carries the per-outlet net-revenue breakdown for the bound tenant in one period. Money is the
 * rule-8 pair — integer minor units + ISO-4217 currency code — never a float. The {@code outlets}
 * list is ordered by {@code revenueMinor} descending (pre-sorted by the read query) so the
 * dashboard can render a ranked outlet list without a client-side sort. A period with no postings
 * yields an empty {@code outlets} list; the endpoint returns {@code 204 No Content} in that case
 * (the controller never invents a currency — rule HR-9).
 *
 * @param period the accounting period {@code YYYY-MM}
 * @param currency the ISO-4217 currency code shared by all outlet rows in the period
 * @param outlets per-outlet net-revenue rows, ordered by revenue desc
 */
public record OutletRevenueResponse(String period, String currency, List<OutletRow> outlets) {

  /**
   * One row in the outlet breakdown.
   *
   * @param businessId the outlet's opaque business-unit UUID (carries across from {@code
   *     SaleRecorded.business_id})
   * @param revenueMinor accumulated net revenue in minor units; may be negative if refunds outpace
   *     sales within the period
   * @param outletName the org-unit display name resolved from the {@code org_unit_ref} local read
   *     model, or {@code null} when the ref row has not yet been hydrated by the consumer (names
   *     may lag until the {@code OrgUnitCreated} event is consumed — callers must tolerate {@code
   *     null})
   */
  public record OutletRow(UUID businessId, long revenueMinor, String outletName) {}
}
