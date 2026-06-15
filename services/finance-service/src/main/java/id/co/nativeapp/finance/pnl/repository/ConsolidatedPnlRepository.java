package id.co.nativeapp.finance.pnl.repository;

import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link ConsolidatedPnl} P&amp;L read model.
 *
 * <p>A thin data port: a derived query only, no business logic, no {@code Money} arithmetic, no
 * manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS GUC on
 * every {@code @Transactional} method (rule 5). The query filters by {@code period} <em>within</em>
 * the bound tenant; RLS adds the {@code company_id} predicate. The write path accumulates the model
 * with an atomic upsert in {@link PnlReadModelWriter}, so there is no find-or-create query here.
 */
public interface ConsolidatedPnlRepository extends JpaRepository<ConsolidatedPnl, UUID> {

  /**
   * Every P&amp;L accumulator for a period (within the bound tenant) — one row per currency. With a
   * single base currency this is normally one row; returning a list keeps the read correct if a
   * tenant ever has postings in more than one currency.
   */
  List<ConsolidatedPnl> findByPeriod(String period);
}
