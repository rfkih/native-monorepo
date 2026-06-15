package id.co.nativeapp.finance.consolidation.domain;

/**
 * The group-level entitlements a request must carry (from its JWT roles) to reach the group
 * consolidation surface (P3d SEAM 4b — the authz layer above the validated lead binding).
 *
 * <p>These are GROUP-scoped roles, distinct from the per-company business roles ({@code owner} /
 * {@code manager} / {@code cashier}) the gateway curates: a teammate of the lead company is granted
 * {@code group:consolidation:view} to READ the consolidation and {@code group:consolidation:close}
 * (a strictly higher, lead-admin entitlement) to TRIGGER a close. The role is necessary but NOT
 * sufficient — the request must ALSO be made by the company that actually LEADS the requested group
 * (validated against the authoritative {@code group_ref}/{@code group_lead} read model in {@link
 * GroupConsolidationService}); the role alone never lets a user bind a group their company does not
 * lead.
 *
 * <p>Two gates, both required (AND), so the surface fails closed: missing the role → {@code 403}
 * (the group scope is never bound, the group RLS policies match nothing); not the lead → {@code
 * 404} (avoid group-existence disclosure).
 */
public final class GroupConsolidationRole {

  /** Read the group consolidation summary (the total + flags + eliminations status). */
  public static final String VIEW = "group:consolidation:view";

  /** Trigger a group close (lead-admin only — a strictly higher entitlement than {@link #VIEW}). */
  public static final String CLOSE = "group:consolidation:close";

  private GroupConsolidationRole() {}
}
