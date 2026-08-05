package id.co.nativeapp.finance.empexpense.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code employee_expense_claim_ledger} row (ADR 0030 §7 settle-once guard + §4
 * employee-payable drill-down source, review W1/S3) — ONE row per claim, carrying the recognition
 * (approval), void, and settlement facts as they land, IN ANY ARRIVAL ORDER. Whichever consumer
 * creates the row first stamps the immutable identity columns ({@code claimId}, {@code employeeId},
 * {@code orgUnitId}, the amount); every consumer then stamps its own column group.
 *
 * <p><strong>Reconciliation, not just a guard.</strong> A settlement arriving before its approval
 * (out-of-order delivery, or a permanently lost approval) self-heals a row via {@link
 * #unrecognizedSettlement}, leaving {@link #isRecognized()} {@code false} — a DETECTABLE signal (a
 * loud WARN at write time; queryable at read time) that account 2600 carries a debit unbacked by a
 * recognition entry, until a late-arriving approval calls {@link #reconcileRecognition} and the row
 * becomes fully reconciled.
 *
 * <p><strong>Settle-once (ADR 0030 §7).</strong> The database {@code UNIQUE (company_id, claim_id)}
 * constraint (V39) still backs this exactly as before: a concurrent racer INSERTING a fresh row for
 * a claim that has NEVER been recognized or settled (two settlements racing before any approval has
 * landed) trips the constraint as a {@code DataIntegrityViolationException}, recovered by a
 * separate-transaction re-read. A settlement landing on an EXISTING unsettled row is a plain UPDATE
 * ({@link #stampSettlement}), not an insert — protected only by the inherited {@link Auditable}
 * {@code @Version} optimistic lock (a residual, not exercised by this phase's tests).
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code employee_expense_claim_ledger}
 * RLS policy (V39, rule 5).
 */
@Entity
@Table(name = "employee_expense_claim_ledger")
public class EmployeeExpenseClaimLedger extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "org_unit_id", nullable = false, updatable = false)
  private UUID orgUnitId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  /**
   * ISO-4217 alphabetic code. Mapped as a fixed-width {@code CHAR(3)} (PostgreSQL {@code bpchar});
   * {@link SqlTypes#CHAR} makes Hibernate's {@code ddl-auto=validate} agree with the migrated
   * column type — the same annotation {@code MoneyEmbeddable.currency} carries. PostgreSQL
   * space-pads {@code CHAR} on read, so {@link #getAmount()} strips before re-validating the code.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "amount_currency", nullable = false, updatable = false, length = 3)
  private String amountCurrency;

  /** Stamped by the APPROVAL consumer (possibly reconciled onto a row a settlement created). */
  @Column(name = "recognized_at")
  private Instant recognizedAt;

  /** The {@code journal_entry} raised at recognition (Dr EXPENSE / Cr 2600). */
  @Column(name = "recognition_entry_id")
  private UUID recognitionEntryId;

  /** Stamped by the VOID consumer. */
  @Column(name = "voided_at")
  private Instant voidedAt;

  /** The {@code journal_entry} raised for the void contra (Dr 2600 / Cr EXPENSE). */
  @Column(name = "void_entry_id")
  private UUID voidEntryId;

  /** Stamped by the SETTLEMENT consumer — {@code non-null} is the settle-once signal. */
  @Column(name = "settled_at")
  private Instant settledAt;

  /** {@code "DIRECT"} or {@code "PAYROLL"} — mirrors {@code ExpenseReimbursementSettled}. */
  @Column(name = "settlement_kind", length = 16)
  private String settlementKind;

  /** The settling payroll run, or {@code null} for a DIRECT settlement. */
  @Column(name = "payroll_run_id")
  private UUID payrollRunId;

  /** The settling run's supersession sequence, or {@code null} for a DIRECT settlement. */
  @Column(name = "run_seq")
  private Integer runSeq;

  /** The {@code journal_entry} raised for the settlement (Dr 2600 / Cr CASH_CLEARING). */
  @Column(name = "settlement_entry_id")
  private UUID settlementEntryId;

  protected EmployeeExpenseClaimLedger() {
    // for JPA
  }

  private EmployeeExpenseClaimLedger(UUID claimId, UUID employeeId, UUID orgUnitId, Money amount) {
    this.id = UUID.randomUUID();
    this.claimId = Objects.requireNonNull(claimId, "claimId");
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(amount, "amount");
    this.amountMinor = amount.amountMinor();
    this.amountCurrency = amount.currency().getCurrencyCode();
  }

  /**
   * Creates the row via the NORMAL, in-order path: the APPROVAL consumer recognizing a fresh claim.
   */
  public static EmployeeExpenseClaimLedger recognized(
      UUID claimId,
      UUID employeeId,
      UUID orgUnitId,
      Money amount,
      Instant recognizedAt,
      UUID recognitionEntryId) {
    EmployeeExpenseClaimLedger row =
        new EmployeeExpenseClaimLedger(claimId, employeeId, orgUnitId, amount);
    row.recognizedAt = Objects.requireNonNull(recognizedAt, "recognizedAt");
    row.recognitionEntryId = Objects.requireNonNull(recognitionEntryId, "recognitionEntryId");
    return row;
  }

  /**
   * Creates the row via the OUT-OF-ORDER path: the SETTLEMENT consumer finds no row for the claim
   * (the approval is missing or has not arrived yet) and self-heals one carrying ONLY the
   * settlement facts — {@link #isRecognized()} is {@code false} until a late-arriving approval
   * calls {@link #reconcileRecognition}.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static EmployeeExpenseClaimLedger unrecognizedSettlement(
      UUID claimId,
      UUID employeeId,
      UUID orgUnitId,
      Money amount,
      Instant settledAt,
      String settlementKind,
      UUID payrollRunId,
      Integer runSeq,
      UUID settlementEntryId) {
    EmployeeExpenseClaimLedger row =
        new EmployeeExpenseClaimLedger(claimId, employeeId, orgUnitId, amount);
    row.stampSettlement(settledAt, settlementKind, payrollRunId, runSeq, settlementEntryId);
    return row;
  }

  /**
   * Creates the row via the OUT-OF-ORDER path: the VOID consumer finds no row for the claim (the
   * approval has not arrived yet — cross-topic reorder, QA sweep 2026-08-05) and self-heals one
   * carrying ONLY the void facts. A late-arriving approval then calls {@link
   * #reconcileRecognition}, which touches only the recognition columns — {@code voided_at} stays
   * stamped, so the drill-down never shows an actually-voided claim as outstanding.
   */
  public static EmployeeExpenseClaimLedger unrecognizedVoid(
      UUID claimId,
      UUID employeeId,
      UUID orgUnitId,
      Money amount,
      Instant voidedAt,
      UUID voidEntryId) {
    EmployeeExpenseClaimLedger row =
        new EmployeeExpenseClaimLedger(claimId, employeeId, orgUnitId, amount);
    row.applyVoid(voidedAt, voidEntryId);
    return row;
  }

  /**
   * Reconciles a late-arriving APPROVAL onto a row an out-of-order settlement already created. Only
   * the recognition columns are touched — the identity columns were already stamped correctly by
   * the settlement's insert (both events describe the same claim).
   */
  public void reconcileRecognition(Instant recognizedAt, UUID recognitionEntryId) {
    this.recognizedAt = Objects.requireNonNull(recognizedAt, "recognizedAt");
    this.recognitionEntryId = Objects.requireNonNull(recognitionEntryId, "recognitionEntryId");
  }

  /** Stamps the settlement column group (used both by the self-heal factory and by an UPDATE). */
  public void stampSettlement(
      Instant settledAt,
      String settlementKind,
      UUID payrollRunId,
      Integer runSeq,
      UUID settlementEntryId) {
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
    this.settlementKind = Objects.requireNonNull(settlementKind, "settlementKind");
    this.payrollRunId = payrollRunId;
    this.runSeq = runSeq;
    this.settlementEntryId = Objects.requireNonNull(settlementEntryId, "settlementEntryId");
  }

  /** Stamps the void column group. */
  public void applyVoid(Instant voidedAt, UUID voidEntryId) {
    this.voidedAt = Objects.requireNonNull(voidedAt, "voidedAt");
    this.voidEntryId = Objects.requireNonNull(voidEntryId, "voidEntryId");
  }

  /** {@code true} once a settlement has landed — the settle-once signal. */
  public boolean isSettled() {
    return settledAt != null;
  }

  /** {@code true} once an approval has landed (recognition fields stamped). */
  public boolean isRecognized() {
    return recognizedAt != null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getClaimId() {
    return claimId;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  /** The claim amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return Money.ofMinor(amountMinor, amountCurrency.strip());
  }

  public Instant getRecognizedAt() {
    return recognizedAt;
  }

  public UUID getRecognitionEntryId() {
    return recognitionEntryId;
  }

  public Instant getVoidedAt() {
    return voidedAt;
  }

  public UUID getVoidEntryId() {
    return voidEntryId;
  }

  public Instant getSettledAt() {
    return settledAt;
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

  public UUID getSettlementEntryId() {
    return settlementEntryId;
  }
}
