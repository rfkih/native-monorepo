package id.co.nativeapp.finance.empexpense.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code employee_expense_settlement} guard row (ADR 0030 §7) — the settle-once invariant for
 * the expense-claims program. A {@code UNIQUE (company_id, claim_id)} database constraint (V39)
 * backs this: at most one settlement row can ever exist per claim, so a second {@code
 * ExpenseReimbursementSettled} for the same claim — a Kafka re-delivery, or a re-emission after a
 * payroll run's supersession released and re-linked the claim — is a logged no-op rather than a
 * double post. Claim amounts are immutable after approval, so any re-settlement would be
 * financially identical; this single row collapses every double-pay window.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code employee_expense_settlement}
 * RLS policy (V39, rule 5).
 */
@Entity
@Table(name = "employee_expense_settlement")
public class EmployeeExpenseSettlement extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  /** {@code "DIRECT"} or {@code "PAYROLL"} — mirrors {@code ExpenseReimbursementSettled}. */
  @Column(name = "settlement_kind", nullable = false, updatable = false, length = 16)
  private String settlementKind;

  /** The settling payroll run, or {@code null} for a DIRECT settlement. */
  @Column(name = "payroll_run_id", updatable = false)
  private UUID payrollRunId;

  /** The settling run's supersession sequence, or {@code null} for a DIRECT settlement. */
  @Column(name = "run_seq", updatable = false)
  private Integer runSeq;

  /**
   * The {@code journal_entry} raised for this settlement (Dr EMPLOYEE_EXPENSE_PAYABLE / Cr
   * CASH_CLEARING).
   */
  @Column(name = "journal_entry_id", nullable = false, updatable = false)
  private UUID journalEntryId;

  @Column(name = "settled_at", nullable = false, updatable = false)
  private Instant settledAt;

  protected EmployeeExpenseSettlement() {
    // for JPA
  }

  public EmployeeExpenseSettlement(
      UUID claimId,
      String settlementKind,
      UUID payrollRunId,
      Integer runSeq,
      UUID journalEntryId,
      Instant settledAt) {
    this.id = UUID.randomUUID();
    this.claimId = Objects.requireNonNull(claimId, "claimId");
    this.settlementKind = Objects.requireNonNull(settlementKind, "settlementKind");
    this.payrollRunId = payrollRunId;
    this.runSeq = runSeq;
    this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getClaimId() {
    return claimId;
  }

  public String getSettlementKind() {
    return settlementKind;
  }

  public UUID getPayrollRunId() {
    return payrollRunId;
  }

  public Integer getRunSeq() {
    return runSeq;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }

  public Instant getSettledAt() {
    return settledAt;
  }
}
