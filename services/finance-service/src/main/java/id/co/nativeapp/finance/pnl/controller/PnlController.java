package id.co.nativeapp.finance.pnl.controller;

import id.co.nativeapp.finance.fx.dto.PnlPresentation;
import id.co.nativeapp.finance.fx.service.PresentationConverter;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.dto.PnlResponse;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import jakarta.validation.constraints.Pattern;
import java.util.Currency;
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
 *
 * <p><strong>Presentation currency (P3c, additive).</strong> An OPTIONAL {@code presentation=XXX}
 * query param requests a VIEW-ONLY conversion of each P&amp;L leg into that ISO-4217 currency at
 * the period AVERAGE rate (net derived in the presentation currency). It is NOT a base-currency
 * change and NOT a dashboard toggle — a transient per-request view lens. Absent → today's behaviour
 * exactly (existing tests unchanged). Present → the native figures are still returned, plus the
 * converted presentation fields. If EITHER leg has no resolvable rate the request fails loudly with
 * a {@code 422} (the advice), atomically — a half-converted P&amp;L is never returned.
 */
@RestController
@RequestMapping("/api/v1/pnl")
@Validated
public class PnlController {

  private final PnlReader pnlReader;
  private final PresentationConverter presentationConverter;

  public PnlController(PnlReader pnlReader, PresentationConverter presentationConverter) {
    this.pnlReader = pnlReader;
    this.presentationConverter = presentationConverter;
  }

  /**
   * Consolidated P&amp;L for {@code period} (e.g. {@code 2026-06}), within the bound tenant.
   *
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   * @param currency optional ISO-4217 code used only to render a zero P&amp;L for an empty period
   * @param presentation optional ISO-4217 code requesting a view-only conversion of the P&amp;L
   */
  @GetMapping
  public ResponseEntity<PnlResponse> pnl(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String presentation) {

    Optional<ConsolidatedPnl> pnl = pnlReader.pnlForPeriod(period);
    if (pnl.isPresent()) {
      ConsolidatedPnl row = pnl.get();
      if (presentation != null && !presentation.isBlank()) {
        // VIEW-ONLY: convert each leg on the way out; the stored read model is never touched. The
        // converter reads the row's legs (entity access stays out of the controller). An unknown
        // ISO-4217 code -> 400 via Currency.getInstance/the shared advice; no rate -> 422.
        Currency target = Currency.getInstance(presentation.strip());
        PnlPresentation view = presentationConverter.convertPnl(row, period, target);
        return ResponseEntity.ok(PnlResponse.withPresentation(row, view));
      }
      return ResponseEntity.ok(PnlResponse.from(row));
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
