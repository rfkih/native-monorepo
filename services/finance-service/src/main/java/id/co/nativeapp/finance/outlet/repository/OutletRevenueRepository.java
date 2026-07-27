package id.co.nativeapp.finance.outlet.repository;

import id.co.nativeapp.finance.outlet.domain.OutletRevenue;
import id.co.nativeapp.finance.outlet.projection.OutletRevenueView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Read port for the {@code outlet_revenue} accumulator table.
 *
 * <p>A thin data port: one native read query selecting only the columns the outlet-revenue endpoint
 * needs into an {@link OutletRevenueView} projection — never {@code SELECT *} of the entity. Tenant
 * scoping comes solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule
 * 5); there is no manual {@code WHERE company_id} in the query. The write path accumulates via an
 * atomic {@code INSERT … ON CONFLICT … DO UPDATE} executed directly via {@code JdbcTemplate} in
 * {@link id.co.nativeapp.finance.revenue.service.RevenuePostingWriter} and {@link
 * id.co.nativeapp.finance.reversal.service.ReversalPostingWriter} (no find-or-create here).
 */
public interface OutletRevenueRepository extends JpaRepository<OutletRevenue, UUID> {

  /**
   * All outlet-revenue accumulators for a period (within the bound tenant via RLS), LEFT-JOINed
   * against {@code org_unit_ref} to resolve the outlet's display name, ordered by {@code
   * revenue_minor} descending so the dashboard's outlet list is already ranked.
   *
   * <p>The JOIN is a LEFT JOIN so a {@code business_id} with no {@code org_unit_ref} row (the
   * org-unit event not yet consumed) still returns the revenue row — with {@code outlet_name =
   * null}. The read NEVER blocks on hydration (rule 2 — the ref row may lag). Both tables are under
   * FORCE RLS scoped to the session tenant GUC, so no cross-tenant data leaks through the join.
   *
   * <p>Selects only the needed columns into the {@link OutletRevenueView} projection — never the
   * full entity row (read paths select only what they need, via projection interfaces).
   */
  @Query(
      value =
          "SELECT or_.business_id, or_.revenue_minor, or_.currency, our.name AS outlet_name"
              + " FROM outlet_revenue or_"
              + " LEFT JOIN org_unit_ref our ON our.org_unit_id = or_.business_id"
              + " WHERE or_.period = :period"
              + " ORDER BY or_.revenue_minor DESC",
      nativeQuery = true)
  List<OutletRevenueView> findByPeriodOrderByRevenueDesc(String period);
}
