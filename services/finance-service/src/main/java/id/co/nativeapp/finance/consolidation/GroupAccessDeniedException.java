package id.co.nativeapp.finance.consolidation;

/**
 * The requester's company is NOT the lead of the requested group (P3d SEAM 4b — the lead-validation
 * crux). The candidate lead (the JWT-bound {@code company_id}) was checked against the
 * authoritative {@code group_ref}/{@code group_lead} read model and did not match the group's lead
 * — OR the group is unknown to finance. EITHER way the request is denied WITHOUT binding the group
 * scope, so the two-GUC group RLS never engages and a member can never bind a group it does not
 * lead.
 *
 * <p><strong>Mapped to {@code 404}, not {@code 403}, on purpose</strong> — a member company probing
 * for groups it does not lead must NOT be able to distinguish "this group exists but you do not
 * lead it" from "no such group". Returning {@code 404} for both avoids group-existence disclosure
 * (ENGINEERING-STANDARDS — do not leak the existence of a resource the caller may not access).
 * Surfaced as an RFC-7807 ProblemDetail by {@code GroupConsolidationAdvice}.
 */
public class GroupAccessDeniedException extends RuntimeException {

  public GroupAccessDeniedException(String message) {
    super(message);
  }
}
