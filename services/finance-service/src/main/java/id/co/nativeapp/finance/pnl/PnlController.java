package id.co.nativeapp.finance.pnl;

import id.co.nativeapp.money.Money;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/pnl} — the consolidated P&amp;L query for the bound tenant (#21).
 *
 * <p>Returns the tenant's revenue, expense, and net (revenue − expense) for a period as the rule-8
 * Money pair (minor units + ISO-4217 currency) in a {@link PnlResponse}. The tenant ({@code
 * company_id}) comes from the bound {@link id.co.nativeapp.tenant.TenantContext} (set at the
 * request edge by the gateway / dev filter), never from the query — and the read carries no manual
 * {@code WHERE company_id}; RLS scopes it (rule 5).
 *
 * <p>A period with no postings yet has no stored currency. To return a zero P&amp;L the caller may
 * pass the company base currency via the optional {@code currency} param (the dashboard knows it
 * from org-service); with data present that param is ignored. With neither data nor a {@code
 * currency} hint, the endpoint returns {@code 204 No Content} rather than inventing a currency
 * (currencies are company config, not hardcoded in finance — HR-9 / config standard). Mirrors
 * {@code GET /api/v1/revenue}.
 */
@RestController
@RequestMapping("/api/v1/pnl")
@Validated
public class PnlController {

  private final PnlReader pnlReader;

  public PnlController(PnlReader pnlReader) {
    this.pnlReader = pnlReader;
  }

  /**
   * Consolidated P&amp;L for {@code period} (e.g. {@code 2026-06}), within the bound tenant.
   *
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   * @param currency optional ISO-4217 code used only to render a zero P&amp;L for an empty period
   */
  @GetMapping
  public ResponseEntity<PnlResponse> pnl(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period,
      @RequestParam(required = false) String currency) {

    Optional<ConsolidatedPnl> pnl = pnlReader.pnlForPeriod(period);
    if (pnl.isPresent()) {
      return ResponseEntity.ok(PnlResponse.from(pnl.get()));
    }
    if (currency != null && !currency.isBlank()) {
      // No postings for the period yet: a zero P&L in the caller-supplied base currency.
      // Money.ofMinor validates the ISO-4217 code (an unknown code -> 400 via the advice).
      Money zero = Money.ofMinor(0L, currency.strip());
      return ResponseEntity.ok(PnlResponse.zero(period, zero.currency().getCurrencyCode()));
    }
    // No data and no currency hint: do not invent a currency.
    return ResponseEntity.noContent().build();
  }
}
