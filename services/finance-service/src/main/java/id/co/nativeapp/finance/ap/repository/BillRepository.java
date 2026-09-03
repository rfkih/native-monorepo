package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.Bill;
import id.co.nativeapp.finance.ap.projection.AgingBillView;
import id.co.nativeapp.finance.ap.projection.BillDetailView;
import id.co.nativeapp.finance.ap.projection.BillLineView;
import id.co.nativeapp.finance.ap.projection.BillSummaryView;
import id.co.nativeapp.finance.ap.projection.PaymentView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link Bill} aggregate. Inherited {@code findById}/{@code save} are the write
 * path (whole aggregate); the native-query projections below are the read path (only the needed
 * columns). All access is RLS-scoped to the bound tenant — the {@code bill}/{@code vendor}/{@code
 * bill_line}/{@code bill_payment} tables are all under FORCE RLS, so the joins never cross tenants
 * and there is no manual {@code WHERE company_id}.
 */
public interface BillRepository extends JpaRepository<Bill, UUID> {

  /**
   * The bill list (header + vendor name), with optional {@code status} / {@code vendorId} / {@code
   * period} (bill-month {@code YYYY-MM}) filters — each passed as a nullable String; a null
   * disables that filter. {@code vendorId} is cast to uuid so a null binds without a type-inference
   * error. Ordered newest-posted first (drafts, with a null bill date, sort last).
   */
  @Query(
      value =
          "SELECT b.id AS id, b.bill_number AS bill_number, b.vendor_id AS vendor_id,"
              + " v.name AS vendor_name, b.status AS status, b.bill_date AS bill_date,"
              + " b.due_date AS due_date, b.currency AS currency, b.total_minor AS total_minor,"
              + " b.paid_minor AS paid_minor"
              + " FROM bill b JOIN vendor v ON v.id = b.vendor_id"
              + " WHERE (:status IS NULL OR b.status = :status)"
              + " AND (CAST(:vendorId AS uuid) IS NULL OR b.vendor_id = CAST(:vendorId AS uuid))"
              + " AND (:period IS NULL OR to_char(b.bill_date, 'YYYY-MM') = :period)"
              // Bounded at 500 (mirroring AR's code-review M3) as an interim guard against an
              // unbounded scan; a paginated envelope is a tracked follow-up.
              + " ORDER BY b.bill_date DESC NULLS LAST, b.created_at DESC LIMIT 500",
      nativeQuery = true)
  List<BillSummaryView> findSummaries(String status, String vendorId, String period);

  /** The bill detail header (bill joined to vendor name) for one id (RLS-scoped). */
  @Query(
      value =
          "SELECT b.id AS id, b.bill_number AS bill_number, b.vendor_id AS vendor_id,"
              + " v.name AS vendor_name, b.status AS status, b.bill_date AS bill_date,"
              + " b.due_date AS due_date, b.currency AS currency, b.subtotal_minor AS"
              + " subtotal_minor, b.tax_minor AS tax_minor, b.total_minor AS total_minor,"
              + " b.paid_minor AS paid_minor, b.uses_illustrative_rules AS uses_illustrative_rules"
              + " FROM bill b JOIN vendor v ON v.id = b.vendor_id WHERE b.id = :id",
      nativeQuery = true)
  Optional<BillDetailView> findDetail(UUID id);

  /** The lines of one bill, ordered by line number. */
  @Query(
      value =
          "SELECT line_no AS line_no, description, quantity, unit_price_minor AS unit_price_minor,"
              + " line_total_minor AS line_total_minor, is_inventory AS is_inventory,"
              + " ingredient_id AS ingredient_id, ingredient_name AS ingredient_name,"
              + " ingredient_qty_base AS ingredient_qty_base"
              + " FROM bill_line WHERE bill_id = :billId ORDER BY line_no",
      nativeQuery = true)
  List<BillLineView> findLines(UUID billId);

  /** The payments of one bill, oldest first. */
  @Query(
      value =
          "SELECT id, amount_minor AS amount_minor, currency, paid_at AS paid_at, method"
              + " FROM bill_payment WHERE bill_id = :billId ORDER BY paid_at",
      nativeQuery = true)
  List<PaymentView> findPayments(UUID billId);

  /**
   * All outstanding bills (POSTED / PARTIALLY_PAID with a positive balance) joined to the vendor
   * name — the input to the AP aging report, which buckets them by days-overdue in the reader.
   */
  @Query(
      value =
          "SELECT b.vendor_id AS vendor_id, v.name AS vendor_name, b.currency AS currency,"
              + " b.due_date AS due_date, (b.total_minor - b.paid_minor) AS outstanding_minor"
              + " FROM bill b JOIN vendor v ON v.id = b.vendor_id"
              + " WHERE b.status IN ('POSTED', 'PARTIALLY_PAID') AND b.total_minor > b.paid_minor",
      nativeQuery = true)
  List<AgingBillView> findOutstanding();
}
