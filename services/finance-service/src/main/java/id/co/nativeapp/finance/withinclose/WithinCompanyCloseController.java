package id.co.nativeapp.finance.withinclose;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/closes} — finalise the bound company's period (P3d SEAM 4a — THE PRODUCERS).
 *
 * <p>Gathers the company's balanced trial balance from the ledger, records the within-company
 * close, and EMITS the loop-closing events atomically (rule 3): {@code ConsolidationClosed(group_id
 * = null)} (consumed by notification-service) and one {@code TrialBalancePublished} per group the
 * company belongs to (consumed by the SEAM-2 group ingest). The tenant ({@code company_id}) comes
 * from the bound {@link id.co.nativeapp.tenant.TenantContext} (set at the request edge by the
 * gateway / dev filter), never from the body — and every read/write is RLS-scoped (rule 5).
 *
 * <p><strong>Idempotent.</strong> A re-close of an already-closed {@code (company, period)} returns
 * {@code 200} with {@code firstClose = false} and re-emits nothing.
 *
 * <p>The GROUP authz-gated query endpoint + role is SEAM 4b — NOT built here; this seam ships only
 * the producer path (the within-company close that EMITS).
 */
@RestController
@RequestMapping("/api/v1/closes")
public class WithinCompanyCloseController {

  private final WithinCompanyCloseService closeService;

  public WithinCompanyCloseController(WithinCompanyCloseService closeService) {
    this.closeService = closeService;
  }

  /**
   * Closes the bound company's period.
   *
   * @param request the period {@code YYYY-MM} + an OPTIONAL base-currency cross-check (the base
   *     currency is derived from the ledger, not the body; a supplied mismatch fails loud)
   */
  @PostMapping
  public ResponseEntity<WithinCompanyCloseResponse> close(
      @Valid @RequestBody WithinCompanyCloseRequest request) {
    WithinCompanyCloseResult result = closeService.close(request.period(), request.baseCurrency());
    return ResponseEntity.ok(WithinCompanyCloseResponse.from(result));
  }
}
