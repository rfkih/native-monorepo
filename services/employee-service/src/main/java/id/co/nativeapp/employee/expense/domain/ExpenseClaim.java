package id.co.nativeapp.employee.expense.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code expense_claim} aggregate — an employee's expense, from a {@code DRAFT} they own
 * through a manager's decision to reimbursement (ADR 0030, the expense-claims program). It owns its
 * guarded transitions ({@link #submit()}, {@link #approve}, {@link #refuse}, {@link #cancel()});
 * every illegal one throws {@link ClaimStateException} (→ 409).
 *
 * <p><strong>Money (rule 8).</strong> {@code amount} is stored as {@link MoneyEmbeddable} (plain
 * {@code amount_minor}/{@code amount_currency} columns) — deliberately NOT PII-encrypted like a
 * payslip line: a claim amount is manager-visible operational data (ADR 0030), never a float. v1
 * claims are always in the tenant's established currency (see {@code ExpenseClaimWriter} Javadoc —
 * employee-service has no other persisted source of a company base currency).
 *
 * <p><strong>{@code org_unit_id}</strong> is a SNAPSHOT taken at create/edit time of the employee's
 * active assignment as of {@code expense_date} (the {@code LaborCostAllocator} / {@code
 * PayrollRunWriter} idiom). When the employee has no active assignment, {@code ExpenseClaimWriter}
 * stamps {@code PayrollRunWriter.UNALLOCATED_OUTLET} (the SAME all-zeros UUID sentinel the payroll
 * engine already uses for the identical "no outlet" case) — reused rather than a second, redundant
 * sentinel defined here — so money/attribution is never dropped, only visibly unattributed.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code expense_claim} RLS policy (rule 5, V9).
 */
@Entity
@Table(name = "expense_claim")
public class ExpenseClaim extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "org_unit_id", nullable = false)
  private UUID orgUnitId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ClaimStatus status;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "expense_date", nullable = false)
  private LocalDate expenseDate;

  @Column(name = "merchant", length = 160)
  private String merchant;

  @Column(name = "note")
  private String note;

  @Enumerated(EnumType.STRING)
  @Column(name = "reimbursement_method", nullable = false, length = 16)
  private ReimbursementMethod reimbursementMethod;

  @Column(name = "reimbursement_run_id")
  private UUID reimbursementRunId;

  @Column(name = "settled_at")
  private Instant settledAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "decided_by", length = 255)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decision_comment")
  private String decisionComment;

  protected ExpenseClaim() {
    // for JPA
  }

  /**
   * Creates a DRAFT claim with a freshly generated id.
   *
   * @param employeeId the claiming employee; must be non-null
   * @param categoryId the expense category; must be non-null
   * @param orgUnitId the assignment-derived dimension snapshot; must be non-null (the caller stamps
   *     {@code PayrollRunWriter.UNALLOCATED_OUTLET} when the employee has no active assignment)
   * @param amount the claim amount; must be strictly positive
   * @param expenseDate the date the expense was incurred; must be non-null
   * @param merchant an optional merchant name
   * @param note an optional free-text note
   * @param reimbursementMethod the chosen reimbursement path; {@code null} defaults to {@link
   *     ReimbursementMethod#PAYROLL}
   */
  public ExpenseClaim(
      UUID employeeId,
      UUID categoryId,
      UUID orgUnitId,
      Money amount,
      LocalDate expenseDate,
      String merchant,
      String note,
      ReimbursementMethod reimbursementMethod) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.status = ClaimStatus.DRAFT;
    this.reimbursementMethod =
        reimbursementMethod == null ? ReimbursementMethod.PAYROLL : reimbursementMethod;
    applyEditableFields(categoryId, orgUnitId, amount, expenseDate, merchant, note);
  }

  /**
   * Applies an edit to every editable field, allowed ONLY while {@code DRAFT} — the same fields the
   * constructor validates.
   *
   * @throws ClaimStateException if the claim is not DRAFT (→ 409)
   * @throws IllegalArgumentException if {@code amount} is not strictly positive (→ 400)
   */
  public void applyDraftEdit(
      UUID categoryId,
      UUID orgUnitId,
      Money amount,
      LocalDate expenseDate,
      String merchant,
      String note,
      ReimbursementMethod reimbursementMethod) {
    requireStatus(ClaimStatus.DRAFT, "edit");
    this.reimbursementMethod =
        reimbursementMethod == null ? ReimbursementMethod.PAYROLL : reimbursementMethod;
    applyEditableFields(categoryId, orgUnitId, amount, expenseDate, merchant, note);
  }

  private void applyEditableFields(
      UUID categoryId,
      UUID orgUnitId,
      Money amount,
      LocalDate expenseDate,
      String merchant,
      String note) {
    this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException(
          "expense claim amount must be strictly positive: " + amount);
    }
    this.amount = MoneyEmbeddable.of(amount);
    this.expenseDate = Objects.requireNonNull(expenseDate, "expenseDate");
    this.merchant = blankToNull(merchant);
    this.note = blankToNull(note);
  }

  /**
   * Transitions {@code DRAFT -> SUBMITTED}.
   *
   * @throws ClaimStateException if not DRAFT (→ 409)
   */
  public void submit() {
    requireStatus(ClaimStatus.DRAFT, "submit");
    this.status = ClaimStatus.SUBMITTED;
  }

  /**
   * Transitions {@code SUBMITTED -> APPROVED}, stamping the decision. Expense recognition happens
   * HERE (ADR 0030 §2) — the caller emits {@code ExpenseClaimApproved} in the SAME transaction as
   * this call (rule 3).
   *
   * @param actor the approving login (never the claim's own employee — enforced by the writer, not
   *     this aggregate, since the aggregate does not know the caller's employee identity)
   * @param comment an optional approval comment
   * @param approvedAt the approval instant; also stamped as {@code decidedAt}
   * @throws ClaimStateException if not SUBMITTED (→ 409)
   */
  public void approve(String actor, String comment, Instant approvedAt) {
    requireStatus(ClaimStatus.SUBMITTED, "approve");
    this.status = ClaimStatus.APPROVED;
    this.approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
    this.decidedBy = requireNonBlank(actor, "actor");
    this.decidedAt = approvedAt;
    this.decisionComment = blankToNull(comment);
  }

  /**
   * Transitions {@code SUBMITTED -> REFUSED}, stamping the decision. A refusal requires a comment —
   * the employee needs a reason to fix and resubmit.
   *
   * @throws ClaimStateException if not SUBMITTED (→ 409)
   * @throws RefusalCommentRequiredException if {@code comment} is blank (→ 422)
   */
  public void refuse(String actor, String comment, Instant decidedAt) {
    requireStatus(ClaimStatus.SUBMITTED, "refuse");
    if (comment == null || comment.isBlank()) {
      throw new RefusalCommentRequiredException(this.id);
    }
    this.status = ClaimStatus.REFUSED;
    this.decidedBy = requireNonBlank(actor, "actor");
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    this.decisionComment = comment.strip();
  }

  /**
   * Transitions {@code APPROVED -> REIMBURSED} via the DIRECT pay-now path (ADR 0030 §6, Phase E4)
   * — the manager surface's {@code POST /{id}/pay}. Legal ONLY when the claim is APPROVED AND not
   * yet linked to a payroll run ({@code reimbursement_run_id == null}): the two settlement paths
   * (DIRECT here; PAYROLL via the future {@code ExpenseClaimPayrollLinker}, E5) race-guard each
   * other on that same field. This method enforces the conditional guard; the caller ({@code
   * ExpenseClaimWriter}) relies on the entity's optimistic {@code @Version} (rule 4, {@link
   * Auditable}) to make a concurrent linker race lose cleanly — CONTINGENT on E5's linker UPDATE
   * incrementing {@code version} in the same statement (ADR 0030 §6, BINDING): pay-direct flushes a
   * full-column versioned UPDATE, so a linker that skips the bump would let a stale pay pass its
   * {@code WHERE version =} check and clobber the link back to null — a double payment.
   *
   * @param settledAt the settlement instant; also drives the settlement entry's accounting period
   * @throws ClaimStateException if not APPROVED, or already linked to a payroll run (→ 409)
   */
  public void payDirect(Instant settledAt) {
    if (this.status != ClaimStatus.APPROVED || this.reimbursementRunId != null) {
      throw new ClaimStateException(this.status, "pay");
    }
    this.status = ClaimStatus.REIMBURSED;
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
  }

  /**
   * Transitions {@code APPROVED -> REIMBURSED} via the PAYROLL path (ADR 0030 §6, Phase E5) —
   * {@code ExpenseClaimPayrollLinker#markReimbursedAndEmit} calls this for every claim still linked
   * to a POSTED payroll run once that run actually carries the {@code EXPENSE_REIMBURSEMENT}
   * payslip line (Track P Phase P7; pre-P7 the linker's gate never reaches this call at all — see
   * its class Javadoc). Legal ONLY when APPROVED AND already linked to a payroll run ({@code
   * reimbursement_run_id != null}) — the LINK itself was established earlier by {@code
   * ExpenseClaimRepository#linkForRunChunk}'s own conditional UPDATE; this method never sets or
   * clears {@code reimbursement_run_id}, only the status + settlement stamp, so it carries no
   * version-bump obligation of its own beyond the entity's ordinary optimistic {@code @Version}
   * (the caller's plain {@code save()} increments it via JPA, exactly like {@link #payDirect}).
   *
   * @param settledAt the settlement instant; also drives the settlement entry's accounting period
   * @throws ClaimStateException if not APPROVED, or not linked to a payroll run (→ 409)
   */
  public void settleByPayrollRun(Instant settledAt) {
    if (this.status != ClaimStatus.APPROVED || this.reimbursementRunId == null) {
      throw new ClaimStateException(this.status, "settleByPayrollRun");
    }
    this.status = ClaimStatus.REIMBURSED;
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
  }

  /**
   * Transitions {@code DRAFT | SUBMITTED -> CANCELLED} — the employee withdrawing their own claim.
   *
   * @throws ClaimStateException if neither DRAFT nor SUBMITTED (→ 409)
   */
  public void cancel() {
    if (status != ClaimStatus.DRAFT && status != ClaimStatus.SUBMITTED) {
      throw new ClaimStateException(status, "cancel");
    }
    this.status = ClaimStatus.CANCELLED;
  }

  private void requireStatus(ClaimStatus required, String attempted) {
    if (this.status != required) {
      throw new ClaimStateException(this.status, attempted);
    }
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public ClaimStatus getStatus() {
    return status;
  }

  public Money getAmount() {
    return amount.toMoney();
  }

  public LocalDate getExpenseDate() {
    return expenseDate;
  }

  public String getMerchant() {
    return merchant;
  }

  public String getNote() {
    return note;
  }

  public ReimbursementMethod getReimbursementMethod() {
    return reimbursementMethod;
  }

  public UUID getReimbursementRunId() {
    return reimbursementRunId;
  }

  public Instant getSettledAt() {
    return settledAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getDecisionComment() {
    return decisionComment;
  }

  @Override
  public String toString() {
    return "ExpenseClaim[id=" + id + ", employeeId=" + employeeId + ", status=" + status + "]";
  }
}
