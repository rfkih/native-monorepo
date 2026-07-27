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
   * All outlet-revenue accumulators for a period (within the bound tenant via RLS), ordered by
   * {@code revenue_minor} descending so the dashboard's outlet list is already ranked.
   *
   * <p>Selects only {@code business_id} and {@code revenue_minor} into a projection — never the
   * full accumulator row (read paths select only what they need, via projection interfaces).
   */
  @Query(
      value =
          "SELECT business_id, revenue_minor, currency"
              + " FROM outlet_revenue"
              + " WHERE period = :period"
              + " ORDER BY revenue_minor DESC",
      nativeQuery = true)
  List<OutletRevenueView> findByPeriodOrderByRevenueDesc(String period);
}
