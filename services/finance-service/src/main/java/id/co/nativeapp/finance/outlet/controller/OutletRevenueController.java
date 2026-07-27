package id.co.nativeapp.finance.outlet.controller;

import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.service.OutletRevenueReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/pnl/outlets} — per-outlet net-revenue breakdown for the bound tenant.
 *
 * <p>Returns all outlet accumulators for a period as a ranked list ({@code revenueMinor}
 * descending) inside {@link OutletRevenueResponse}. The tenant ({@code company_id}) comes from the
 * bound {@link id.co.nativeapp.tenant.TenantContext} (set at the request edge by the gateway / dev
 * filter), never from the query — and the read carries no manual {@code WHERE company_id}; RLS
 * scopes it (rule 5).
 *
 * <p>All outlet rows for the same tenant and period share the same currency (single base currency
 * in M1.5); the currency is read directly from the stored accumulator, never invented by the
 * server. When the period has no postings yet the endpoint returns {@code 204 No Content} rather
 * than inventing a currency (currencies are company config, not hardcoded in finance — HR-9).
 *
 * <p>Presentation-currency conversion is intentionally absent: the FX machinery from {@link
 * id.co.nativeapp.finance.revenue.controller.RevenueController} could be reused, but the outlet
 * slice ships first without it — Phase 2 of the outlet-scoping increment can layer it on additively
 * once the outlet names (from the org-unit read model) land.
 */
@Tag(
    name = "Outlets",
    description =
        "Per-outlet net-revenue breakdown for the bound tenant. Returns all outlet accumulators"
            + " for a period as minor units + ISO-4217, ranked by revenue descending. 204 when"
            + " the period has no postings.")
@RestController
@RequestMapping("/api/v1/pnl/outlets")
@Validated
public class OutletRevenueController {

  private final OutletRevenueReader outletRevenueReader;

  public OutletRevenueController(OutletRevenueReader outletRevenueReader) {
    this.outletRevenueReader = outletRevenueReader;
  }

  /**
   * Per-outlet net-revenue breakdown for {@code period} (e.g. {@code 2026-06}), within the bound
   * tenant. Rows are ordered by {@code revenueMinor} descending.
   *
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   */
  @Operation(
      summary = "Per-outlet net-revenue breakdown for a period",
      description =
          "Returns all outlet accumulators for the given YYYY-MM period as Money (minor units"
              + " + ISO-4217), ranked by revenue desc. 204 when no postings exist for the period."
              + " The currency is read from the stored accumulator (not invented by the server).")
  @GetMapping
  public ResponseEntity<OutletRevenueResponse> outlets(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {

    Optional<OutletRevenueResponse> response = outletRevenueReader.outletsForPeriod(period);
    return response.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
  }
}
