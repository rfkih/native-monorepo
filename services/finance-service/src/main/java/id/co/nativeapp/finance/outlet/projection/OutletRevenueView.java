package id.co.nativeapp.finance.outlet.projection;

import java.util.UUID;

/**
 * Read projection over one {@code outlet_revenue} row LEFT-JOINed against {@code org_unit_ref} —
 * only the columns the {@code /pnl/outlets} endpoint needs, never {@code SELECT *} of the entity.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.finance.outlet.repository.OutletRevenueRepository}. Snake-case native-query
 * aliases map to these accessors via Spring Data's projection-interface convention (CLAUDE.md
 * "native-query aliases snake_case; map via projection interfaces"). Placed in a dedicated {@code
 * projection} package per docs/CODE-STRUCTURE.md §3.3 so the ArchUnit {@code Projection} layer rule
 * can enforce that projections are reached only from the service and repository layers.
 *
 * <p>{@code outletName} is nullable — a {@code business_id} with no {@code org_unit_ref} row (the
 * org-unit event has not yet been consumed) returns {@code null}. The read NEVER blocks on
 * hydration; the endpoint returns the revenue row with {@code outletName: null} rather than
 * failing.
 */
public interface OutletRevenueView {

  UUID getBusinessId();

  long getRevenueMinor();

  String getCurrency();

  /** The org-unit display name from {@code org_unit_ref}, or {@code null} if not yet hydrated. */
  String getOutletName();
}
