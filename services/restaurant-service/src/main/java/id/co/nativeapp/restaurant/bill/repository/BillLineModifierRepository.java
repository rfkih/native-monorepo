package id.co.nativeapp.restaurant.bill.repository;

import id.co.nativeapp.restaurant.bill.domain.BillLineModifier;
import id.co.nativeapp.restaurant.bill.projection.BillLineModifierView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BillLineModifier}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies the tenant GUC
 * automatically (rule 5).
 */
public interface BillLineModifierRepository extends JpaRepository<BillLineModifier, UUID> {

  /**
   * All modifier snapshots for a set of bill line ids. Chunked IN queries (≤1000) are the
   * caller's responsibility. RLS-scoped automatically.
   */
  @Query(
      value =
          """
          SELECT blm.bill_line_id      AS bill_line_id,
                 blm.option_id         AS option_id,
                 blm.name_snapshot     AS name_snapshot,
                 blm.price_delta_minor AS price_delta_minor
            FROM bill_line_modifier blm
           WHERE blm.bill_line_id IN (:billLineIds)
          """,
      nativeQuery = true)
  List<BillLineModifierView> findViewsByBillLineIds(@Param("billLineIds") List<UUID> billLineIds);
}
