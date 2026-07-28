package id.co.nativeapp.finance.unitpnl.controller;

import id.co.nativeapp.finance.unitpnl.dto.UnitPnlResponse;
import id.co.nativeapp.finance.unitpnl.service.UnitPnlReader;
import id.co.nativeapp.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/pnl/org-units/{orgUnitId}} — the per-org-unit P&amp;L rollup behind the
 * org-unit hub's Overview tab: unit totals plus a per-child-outlet breakdown (empty for an
 * outlet-type unit).
 *
 * <p>Mirrors {@link id.co.nativeapp.finance.pnl.controller.PnlController} semantics exactly: an
 * unknown / foreign / team unit is a {@code 204} (RLS makes foreign == unknown —
 * anti-enumeration); a known unit with zero postings returns a zero P&amp;L in the caller-supplied
 * {@code currency} hint (the console sends the company base currency), or {@code 204} without the
 * hint — finance never invents a currency. Tenant comes from the bound {@code TenantContext}; the
 * read is RLS-scoped (rule 5).
 */
@Tag(
    name = "P&L",
    description =
        "Per-org-unit Profit & Loss rollup: a business unit rolls up its child outlets; an outlet"
            + " returns its own figures. Money as minor units + ISO-4217 (rule 8).")
@RestController
@RequestMapping("/api/v1/pnl/org-units")
@Validated
public class UnitPnlController {

  private final UnitPnlReader unitPnlReader;

  public UnitPnlController(UnitPnlReader unitPnlReader) {
    this.unitPnlReader = unitPnlReader;
  }

  /**
   * The rollup for {@code orgUnitId} in {@code period}.
   *
   * @param orgUnitId the org-unit id (business unit or outlet)
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   * @param currency optional ISO-4217 code used only to render a zero P&amp;L for an empty period
   */
  @Operation(
      summary = "Per-org-unit P&L rollup for a period",
      description =
          "Returns the unit's revenue, expense, and net for the YYYY-MM period plus a per-outlet"
              + " breakdown (a business unit rolls up its child outlets; an outlet has an empty"
              + " breakdown). 204 for an unknown unit, or for an empty period without a currency"
              + " hint. Money as minor units + ISO-4217.")
  @GetMapping("/{orgUnitId}")
  public ResponseEntity<UnitPnlResponse> unitPnl(
      @PathVariable UUID orgUnitId,
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period,
      @RequestParam(required = false) String currency) {

    Optional<UnitPnlResponse> result = unitPnlReader.unitPnlForPeriod(orgUnitId, period);
    if (result.isEmpty()) {
      return ResponseEntity.noContent().build();
    }
    UnitPnlResponse pnl = result.get();
    if (pnl.currency() != null) {
      return ResponseEntity.ok(pnl);
    }
    if (currency != null && !currency.isBlank()) {
      // Zero postings for the period: a zero P&L in the caller-supplied base currency.
      // Money.ofMinor validates the ISO-4217 code (an unknown code -> 400 via the advice).
      Money zero = Money.ofMinor(0L, currency.strip());
      return ResponseEntity.ok(pnl.withCurrency(zero.currency().getCurrencyCode()));
    }
    // No data and no currency hint: do not invent a currency (PnlController convention).
    return ResponseEntity.noContent().build();
  }
}
