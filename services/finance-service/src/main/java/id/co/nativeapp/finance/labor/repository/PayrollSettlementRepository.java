package id.co.nativeapp.finance.labor.repository;

import id.co.nativeapp.finance.labor.domain.PayrollSettlement;
import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.projection.PayrollSettlementSummaryView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the {@link PayrollSettlement} aggregate (ADR 0032, Track P phase P5).
 * RLS-scoped (rule 5); no business logic, no manual {@code WHERE company_id}.
 */
public interface PayrollSettlementRepository extends JpaRepository<PayrollSettlement, UUID> {

  /** The replay lookup: an existing settlement for this Idempotency-Key, within the tenant. */
  Optional<PayrollSettlement> findByIdempotencyKey(String idempotencyKey);

  /** The one-shot guard read: an existing settlement for this {@code (run, kind)}, if any. */
  Optional<PayrollSettlement> findByPayrollRunLedgerIdAndKind(
      UUID payrollRunLedgerId, SettlementKind kind);

  /**
   * Settlement status rows for a set of run-ledger ids (the liabilities read's settled-badge join,
   * CODE-STRUCTURE §3.3 projection — narrow columns only, never the entity). The caller chunks the
   * id list with {@code Lists.partition} (CLAUDE.md IN-chunking convention) when it may exceed
   * 1000.
   */
  @Query(
      value =
          """
          SELECT ps.payroll_run_ledger_id AS payroll_run_ledger_id,
                 ps.kind                  AS kind,
                 ps.amount_minor          AS amount_minor,
                 ps.amount_currency       AS amount_currency,
                 ps.journal_entry_id      AS journal_entry_id,
                 ps.settled_at            AS settled_at
            FROM payroll_settlement ps
           WHERE ps.payroll_run_ledger_id IN (:runLedgerIds)
          """,
      nativeQuery = true)
  List<PayrollSettlementSummaryView> findSummariesByPayrollRunLedgerIdIn(
      @Param("runLedgerIds") List<UUID> runLedgerIds);
}
