package id.co.nativeapp.finance.withinclose.controller;

import id.co.nativeapp.finance.withinclose.dto.CloseHistoryResponse;
import id.co.nativeapp.finance.withinclose.dto.WithinCompanyCloseRequest;
import id.co.nativeapp.finance.withinclose.dto.WithinCompanyCloseResponse;
import id.co.nativeapp.finance.withinclose.dto.WithinCompanyCloseResult;
import id.co.nativeapp.finance.withinclose.service.CloseHistoryReader;
import id.co.nativeapp.finance.withinclose.service.WithinCompanyCloseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@Tag(
    name = "Period Close",
    description =
        "Finalises the bound company's accounting period (P3d SEAM 4a). Gathers the balanced"
            + " trial balance, records the within-company close, and emits ConsolidationClosed +"
            + " TrialBalancePublished events atomically via the transactional outbox. Idempotent:"
            + " a re-close returns 200 with firstClose=false and re-emits nothing.")
@RestController
@RequestMapping("/api/v1/closes")
public class WithinCompanyCloseController {

  private final WithinCompanyCloseService closeService;
  private final CloseHistoryReader closeHistoryReader;

  public WithinCompanyCloseController(
      WithinCompanyCloseService closeService, CloseHistoryReader closeHistoryReader) {
    this.closeService = closeService;
    this.closeHistoryReader = closeHistoryReader;
  }

  /**
   * Returns the close history for the bound company — all {@code within_company_close} records,
   * most-recent period first. RLS scopes the read to the bound company (rule 5); no {@code WHERE
   * company_id} is in the query. Returns {@code 200} always (empty list when no closes exist).
   *
   * <p>The console's "period close" page uses this list to render a history of past closes with
   * their {@code period}, {@code baseCurrency}, {@code reconciled}, and {@code
   * usesIllustrativeRules} flags.
   */
  @Operation(
      summary = "List historical closes for the bound company",
      description =
          "Returns all within-company close records for the bound company (most-recent period"
              + " first). 200 always (empty list when no closes exist). RLS-scoped (rule 5)."
              + " Each item: closeId, period (YYYY-MM), baseCurrency (ISO-4217), firstClose"
              + " (always true — only real first-close rows are persisted), reconciled,"
              + " usesIllustrativeRules.")
  @GetMapping
  public ResponseEntity<List<CloseHistoryResponse>> listCloses() {
    return ResponseEntity.ok(closeHistoryReader.findAllForCurrentTenant());
  }

  /**
   * Closes the bound company's period.
   *
   * @param request the period {@code YYYY-MM} + an OPTIONAL base-currency cross-check (the base
   *     currency is derived from the ledger, not the body; a supplied mismatch fails loud)
   */
  @Operation(
      summary = "Close the bound company's accounting period",
      description =
          "Closes the bound company's period (YYYY-MM). The body carries the period and an optional"
              + " base-currency cross-check; a supplied mismatch with the ledger's derived currency"
              + " fails loud. Emits ConsolidationClosed and one TrialBalancePublished per group the"
              + " company belongs to. Idempotent: re-close returns 200 with firstClose=false.")
  @PostMapping
  public ResponseEntity<WithinCompanyCloseResponse> close(
      @Valid @RequestBody WithinCompanyCloseRequest request) {
    WithinCompanyCloseResult result = closeService.close(request.period(), request.baseCurrency());
    return ResponseEntity.ok(WithinCompanyCloseResponse.from(result));
  }
}
