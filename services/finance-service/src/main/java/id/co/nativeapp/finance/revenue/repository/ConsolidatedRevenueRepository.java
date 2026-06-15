package id.co.nativeapp.finance.revenue.repository;

import id.co.nativeapp.finance.revenue.domain.ConsolidatedRevenue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link ConsolidatedRevenue} read model.
 *
 * <p>A thin data port: derived queries only, no business logic, no {@code Money} arithmetic, no
 * manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS GUC on
 * every {@code @Transactional} method (rule 5). The query below filters by {@code period}
 * <em>within </em> the bound tenant; RLS adds the {@code company_id} predicate.
 *
 * <p>This is the READ side only: the write path accumulates the read model with an atomic {@code
 * INSERT … ON CONFLICT … DO UPDATE} in {@link RevenuePostingWriter} (no read-modify-write window),
 * so there is no find-or-create query here.
 */
public interface ConsolidatedRevenueRepository extends JpaRepository<ConsolidatedRevenue, UUID> {

  /**
   * Every accumulator for a period (within the bound tenant) — one row per currency. M1.5 is single
   * base currency, so this is normally one row; returning a list keeps the read correct if a tenant
   * ever has postings in more than one currency.
   */
  List<ConsolidatedRevenue> findByPeriod(String period);
}
