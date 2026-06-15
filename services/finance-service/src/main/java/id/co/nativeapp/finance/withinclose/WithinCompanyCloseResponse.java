package id.co.nativeapp.finance.withinclose;

import java.util.List;

/**
 * The {@code POST /api/v1/closes} response body (P3d SEAM 4a) — the outcome of the within-company
 * close, projected from {@link WithinCompanyCloseResult}.
 *
 * @param closeId the {@code within_company_close} row id
 * @param period the accounting period that was closed
 * @param firstClose {@code true} on the close that emitted the events; {@code false} on an
 *     idempotent re-close (which re-emitted nothing)
 * @param reconciled whether the published trial balance reconciled (always true)
 * @param usesIllustrativeRules whether any rolled-up posting was illustrative-placeholder-derived
 * @param trialBalancePublishedGroups the group ids a {@code TrialBalancePublished} was emitted for
 *     (UUID strings; empty when the company belongs to no group)
 */
public record WithinCompanyCloseResponse(
    String closeId,
    String period,
    boolean firstClose,
    boolean reconciled,
    boolean usesIllustrativeRules,
    List<String> trialBalancePublishedGroups) {

  /** Projects the service result onto the wire DTO. */
  public static WithinCompanyCloseResponse from(WithinCompanyCloseResult result) {
    return new WithinCompanyCloseResponse(
        result.closeId().toString(),
        result.period(),
        result.firstClose(),
        result.reconciled(),
        result.usesIllustrativeRules(),
        result.trialBalancePublishedGroups().stream().map(java.util.UUID::toString).toList());
  }
}
