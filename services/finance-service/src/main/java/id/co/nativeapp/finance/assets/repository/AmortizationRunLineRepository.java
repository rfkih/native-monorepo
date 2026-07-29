package id.co.nativeapp.finance.assets.repository;

import id.co.nativeapp.finance.assets.domain.AmortizationRunLine;
import id.co.nativeapp.finance.assets.projection.AssetScheduleLineView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link AmortizationRunLine} (Phase 6). RLS scopes all access (rule 5).
 * The item roll-ups (accumulated depreciation / amortized-to-date) live in the asset/deferral
 * register queries; here is the per-item schedule history read.
 */
public interface AmortizationRunLineRepository extends JpaRepository<AmortizationRunLine, UUID> {

  /**
   * The posted schedule lines of one item (asset or deferral), joined to the run for its period,
   * oldest month first — the asset-detail schedule history. RLS-scoped.
   */
  @Query(
      value =
          """
          SELECT ar.period        AS period,
                 arl.month_index  AS month_index,
                 arl.amount_minor AS amount_minor,
                 arl.currency     AS currency
            FROM amortization_run_line arl
            JOIN amortization_run ar ON ar.id = arl.run_id
           WHERE arl.item_type = :itemType AND arl.item_id = :itemId
           ORDER BY arl.month_index
           LIMIT 600
          """,
      nativeQuery = true)
  List<AssetScheduleLineView> findScheduleLines(
      @Param("itemType") String itemType, @Param("itemId") UUID itemId);
}
