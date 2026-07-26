package id.co.nativeapp.restaurant.bill.repository;

import id.co.nativeapp.restaurant.bill.domain.BillLine;
import id.co.nativeapp.restaurant.bill.projection.BillLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BillLine}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies the tenant GUC
 * automatically (rule 5). Read paths return a narrow projection, never {@code SELECT *}.
 */
public interface BillLineRepository extends JpaRepository<BillLine, UUID> {

  /**
   * All lines for a given bill, projected to {@link BillLineView}. RLS-scoped via the session
   * tenant. Results are ordered by creation time so appended rounds appear in encounter order.
   */
  @Query(
      value =
          """
          SELECT bl.id                    AS id,
                 bl.menu_item_id          AS menu_item_id,
                 bl.name_snapshot         AS name_snapshot,
                 bl.unit_price_minor      AS unit_price_minor,
                 bl.modifier_delta_minor  AS modifier_delta_minor,
                 bl.qty                   AS qty,
                 bl.line_total_minor      AS line_total_minor,
                 bl.paid                  AS paid,
                 bl.paid_sale_id          AS paid_sale_id
            FROM bill_line bl
           WHERE bl.bill_id = :billId
           ORDER BY bl.created_at
          """,
      nativeQuery = true)
  List<BillLineView> findViewsByBillId(@Param("billId") UUID billId);

  /**
   * Bulk-marks a set of bill lines as paid in a single UPDATE. Runs in the caller's transaction
   * (propagation MANDATORY via the {@code @Transactional} on the enclosing {@link
   * id.co.nativeapp.restaurant.bill.service.BillWriter BillWriter} method).
   *
   * <p>RLS is active; only lines visible to the current tenant are updated.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE bill_line
             SET paid          = true,
                 paid_sale_id  = CAST(:saleId AS uuid),
                 updated_at    = NOW(),
                 updated_by    = :updatedBy,
                 version       = version + 1
           WHERE id IN (:lineIds)
          """,
      nativeQuery = true)
  void markLinesPaid(
      @Param("lineIds") List<UUID> lineIds,
      @Param("saleId") UUID saleId,
      @Param("updatedBy") String updatedBy);
}
