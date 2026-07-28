package id.co.nativeapp.finance.ar.repository;

import id.co.nativeapp.finance.ar.domain.Invoice;
import id.co.nativeapp.finance.ar.projection.AgingInvoiceView;
import id.co.nativeapp.finance.ar.projection.InvoiceDetailView;
import id.co.nativeapp.finance.ar.projection.InvoiceLineView;
import id.co.nativeapp.finance.ar.projection.InvoiceSummaryView;
import id.co.nativeapp.finance.ar.projection.PaymentView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link Invoice} aggregate. Inherited {@code findById}/{@code save} are the
 * write path (whole aggregate); the native-query projections below are the read path (only the
 * needed columns). All access is RLS-scoped to the bound tenant — the {@code invoice}/{@code
 * customer}/ {@code invoice_line}/{@code invoice_payment} tables are all under FORCE RLS, so the
 * joins never cross tenants and there is no manual {@code WHERE company_id}.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

  /**
   * The invoice list (header + customer name), with optional {@code status} / {@code customerId} /
   * {@code period} (issue-month {@code YYYY-MM}) filters — each passed as a nullable String; a null
   * disables that filter. {@code customerId} is cast to uuid so a null binds without a
   * type-inference error. Ordered newest-issued first (drafts, with a null issue date, sort last).
   */
  @Query(
      value =
          "SELECT i.id AS id, i.invoice_number AS invoice_number, i.customer_id AS customer_id,"
              + " c.name AS customer_name, i.status AS status, i.issue_date AS issue_date,"
              + " i.due_date AS due_date, i.currency AS currency, i.total_minor AS total_minor,"
              + " i.paid_minor AS paid_minor"
              + " FROM invoice i JOIN customer c ON c.id = i.customer_id"
              + " WHERE (:status IS NULL OR i.status = :status)"
              + " AND (CAST(:customerId AS uuid) IS NULL OR i.customer_id = CAST(:customerId AS uuid))"
              + " AND (:period IS NULL OR to_char(i.issue_date, 'YYYY-MM') = :period)"
              // Bounded at 500 (code-review M3) as an interim guard against an unbounded scan; a
              // paginated envelope is a tracked follow-up.
              + " ORDER BY i.issue_date DESC NULLS LAST, i.created_at DESC LIMIT 500",
      nativeQuery = true)
  List<InvoiceSummaryView> findSummaries(String status, String customerId, String period);

  /** The invoice detail header (invoice joined to customer name) for one id (RLS-scoped). */
  @Query(
      value =
          "SELECT i.id AS id, i.invoice_number AS invoice_number, i.customer_id AS customer_id,"
              + " c.name AS customer_name, i.status AS status, i.issue_date AS issue_date,"
              + " i.due_date AS due_date, i.currency AS currency, i.subtotal_minor AS subtotal_minor,"
              + " i.tax_minor AS tax_minor, i.total_minor AS total_minor, i.paid_minor AS paid_minor,"
              + " i.uses_illustrative_rules AS uses_illustrative_rules"
              + " FROM invoice i JOIN customer c ON c.id = i.customer_id WHERE i.id = :id",
      nativeQuery = true)
  Optional<InvoiceDetailView> findDetail(UUID id);

  /** The lines of one invoice, ordered by line number. */
  @Query(
      value =
          "SELECT line_no AS line_no, description, quantity, unit_price_minor AS unit_price_minor,"
              + " line_total_minor AS line_total_minor"
              + " FROM invoice_line WHERE invoice_id = :invoiceId ORDER BY line_no",
      nativeQuery = true)
  List<InvoiceLineView> findLines(UUID invoiceId);

  /** The payments of one invoice, oldest first. */
  @Query(
      value =
          "SELECT id, amount_minor AS amount_minor, currency, paid_at AS paid_at, method"
              + " FROM invoice_payment WHERE invoice_id = :invoiceId ORDER BY paid_at",
      nativeQuery = true)
  List<PaymentView> findPayments(UUID invoiceId);

  /**
   * All outstanding invoices (ISSUED / PARTIALLY_PAID with a positive balance) joined to the
   * customer name — the input to the AR aging report, which buckets them by days-overdue in the
   * reader.
   */
  @Query(
      value =
          "SELECT i.customer_id AS customer_id, c.name AS customer_name, i.currency AS currency,"
              + " i.due_date AS due_date, (i.total_minor - i.paid_minor) AS outstanding_minor"
              + " FROM invoice i JOIN customer c ON c.id = i.customer_id"
              + " WHERE i.status IN ('ISSUED', 'PARTIALLY_PAID') AND i.total_minor > i.paid_minor",
      nativeQuery = true)
  List<AgingInvoiceView> findOutstanding();
}
