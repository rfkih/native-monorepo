package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.BillLine;
import id.co.nativeapp.finance.ap.projection.BillLineNetView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Write-path repository for {@link BillLine} rows (saved when a draft is created). RLS-scoped. */
public interface BillLineRepository extends JpaRepository<BillLine, UUID> {

  /**
   * The {@code (line_total_minor, is_inventory)} pairs of one bill's lines — the input to {@code
   * BillWriter}'s ADR 0067 Phase B, §3 {@code EXPENSE_NET}/{@code INVENTORY_NET} split. One small
   * per-bill set (the existing {@code idx_bill_line_bill} index, V27); no new query shape.
   */
  @Query(
      value =
          "SELECT line_total_minor AS line_total_minor, is_inventory AS is_inventory"
              + " FROM bill_line WHERE bill_id = :billId",
      nativeQuery = true)
  List<BillLineNetView> findNetViewsByBillId(UUID billId);
}
