package id.co.nativeapp.finance.revenue;

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
 * {@code GET /api/v1/revenue} — the consolidated-revenue query for the bound tenant.
 *
 * <p>Returns the tenant's accumulated revenue for a period as the rule-8 Money pair (minor units +
 * ISO-4217 currency) in a {@link RevenueResponse}. The tenant ({@code company_id}) comes from the
 * bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request edge by the
 * gateway / dev filter), never from the query — and the read carries no manual {@code WHERE
 * company_id}; RLS scopes it (rule 5).
 *
 * <p>A period with no postings yet has no stored currency. To return a zero figure the caller may
 * pass the company base currency via the optional {@code currency} param (the dashboard knows it
 * from org-service); with revenue present that param is ignored. With neither revenue nor a {@code
 * currency} hint, the endpoint returns {@code 204 No Content} rather than inventing a currency
 * (currencies are company config, not hardcoded in finance — HR-9 / config standard).
 */
@RestController
@RequestMapping("/api/v1/revenue")
@Validated
public class RevenueController {

  private final RevenueReader revenueReader;

  public RevenueController(RevenueReader revenueReader) {
    this.revenueReader = revenueReader;
  }

  /**
   * Consolidated revenue for {@code period} (e.g. {@code 2026-06}), within the bound tenant.
   *
   * @param period the accounting period {@code YYYY-MM} (validated)
   * @param currency optional ISO-4217 code used only to render a zero total for an empty period
   */
  @GetMapping
  public ResponseEntity<RevenueResponse> revenue(
      @RequestParam
          @Pattern(
              // YYYY-MM with the month constrained to 01-12, so an impossible month (e.g.
              // 2026-13 / 2026-00) is rejected as a 400, not silently treated as an empty
              // period — a bare \d{4}-\d{2} would accept it. Equivalent to YearMonth.parse
              // succeeding, enforced declaratively so the failure is a validation-failed 400.
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period,
      @RequestParam(required = false) String currency) {

    Optional<Money> revenue = revenueReader.revenueForPeriod(period);
    if (revenue.isPresent()) {
      return ResponseEntity.ok(RevenueResponse.of(period, revenue.get()));
    }
    if (currency != null && !currency.isBlank()) {
      // No postings for the period yet: a zero total in the caller-supplied base currency.
      // Money.ofMinor validates the ISO-4217 code (an unknown code -> 400 via the advice).
      Money zero = Money.ofMinor(0L, currency.strip());
      return ResponseEntity.ok(RevenueResponse.of(period, zero));
    }
    // No data and no currency hint: do not invent a currency.
    return ResponseEntity.noContent().build();
  }
}
