package id.co.nativeapp.finance.consolidation.domain;

/**
 * The requester is authenticated and tenant-bound but does NOT carry the group-level role required
 * for the requested operation (P3d SEAM 4b) — e.g. a read without {@link
 * GroupConsolidationRole#VIEW}, or a close without {@link GroupConsolidationRole#CLOSE}. A
 * tenant/authZ denial → {@code 403} (ENGINEERING-STANDARDS §1.1), surfaced as an RFC-7807
 * ProblemDetail by {@code GroupConsolidationAdvice}.
 *
 * <p><strong>Fail closed.</strong> The denial is raised BEFORE any group scope is bound, so the
 * two-GUC group RLS is never engaged and matches nothing for this request — the role gate and the
 * Postgres policy both keep a member's group books unreachable.
 */
public class GroupRoleDeniedException extends RuntimeException {

  public GroupRoleDeniedException(String message) {
    super(message);
  }
}
