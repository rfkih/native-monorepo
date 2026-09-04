package id.co.nativeapp.finance.companyexpense.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code company_expense} aggregate (ADR 0072) — a first-party company expense recorded by an
 * owner/accountant, posted to the GL at record time. A {@link CompanyExpenseKind#GENERAL} expense
 * is money only; an {@link CompanyExpenseKind#INVENTORY} expense additionally carries {@link
 * CompanyExpenseLine}s that ride to restaurant-service as {@code InventoryPurchaseRecorded} and
 * become priced goods receipts — one submit, money and stock synchronized.
 *
 * <p><strong>Lifecycle.</strong> Born {@code POSTED} (there is no draft — the form is the
 * document); {@link #voidExpense} transitions once to {@code VOID}, linking the contra journal
 * entry. A void is money-side only: stock is never auto-reversed (ADR 0072 §4, fix-forward).
 *
 * <p><strong>Money.</strong> {@code amount_minor} + one {@code currency} column (rule 8); for an
 * INVENTORY expense the amount equals the sum of the lines' {@code value_minor} exactly (writer
 * enforced, both integers).
 *
 * <p>Extends {@link Auditable}; under the {@code company_expense} FORCE-RLS policy (V58).
 */
@Entity
@Table(name = "company_expense")
public class CompanyExpense extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Human-facing sequential number ({@code EXP-00001}), assigned at record time. */
  @Column(name = "expense_no", nullable = false, updatable = false, length = 16)
  private String expenseNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 16)
  private CompanyExpenseKind kind;

  /** The outlet dimension for the P&L ledger posting (NOT the stock-side outlet). */
  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  /** GENERAL only; {@code ""} = the catch-all expense account. INVENTORY rows store {@code ""}. */
  @Column(name = "gl_hint", nullable = false, updatable = false, length = 64)
  private String glHint;

  @Column(name = "description", nullable = false, updatable = false, length = 500)
  private String description;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private CompanyExpenseStatus status;

  /** The journal entry posted at record time (Dr expense-or-GRNI / Cr CASH_CLEARING). */
  @Column(name = "journal_entry_id", nullable = false, updatable = false)
  private UUID journalEntryId;

  /** The contra journal entry, set once on {@link #voidExpense}. */
  @Column(name = "void_journal_entry_id")
  private UUID voidJournalEntryId;

  /** Client-supplied replay key; null when the client sent none. */
  @Column(name = "idempotency_key", updatable = false, length = 64)
  private String idempotencyKey;

  protected CompanyExpense() {
    // for JPA
  }

  /**
   * Records a POSTED company expense. The caller (the writer) has already validated the kind shape,
   * the outlet, the hint, the sealed period and the currency, posted the journal entry, and
   * assigned the number.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static CompanyExpense record(
      UUID id,
      String expenseNo,
      CompanyExpenseKind kind,
      UUID businessId,
      String glHint,
      String description,
      Money amount,
      Instant occurredAt,
      UUID journalEntryId,
      String idempotencyKey) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(amount, "amount");
    if (amount.amountMinor() <= 0) {
      throw new IllegalArgumentException("expense amount must be strictly positive: " + amount);
    }
    CompanyExpense expense = new CompanyExpense();
    expense.id = id;
    expense.expenseNo = Objects.requireNonNull(expenseNo, "expenseNo");
    expense.kind = kind;
    expense.businessId = Objects.requireNonNull(businessId, "businessId");
    expense.glHint = Objects.requireNonNull(glHint, "glHint");
    expense.description = Objects.requireNonNull(description, "description");
    expense.amountMinor = amount.amountMinor();
    expense.currency = amount.currency().getCurrencyCode();
    expense.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    expense.status = CompanyExpenseStatus.POSTED;
    expense.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    expense.idempotencyKey = idempotencyKey;
    return expense;
  }

  /**
   * Transitions POSTED → VOID exactly once, linking the contra entry.
   *
   * @throws CompanyExpenseStateException if already VOID
   */
  public void voidExpense(UUID contraJournalEntryId) {
    if (status != CompanyExpenseStatus.POSTED) {
      throw new CompanyExpenseStateException(
          "only a POSTED company expense can be voided; current status=" + status);
    }
    this.status = CompanyExpenseStatus.VOID;
    this.voidJournalEntryId = Objects.requireNonNull(contraJournalEntryId, "contraJournalEntryId");
  }

  public UUID getId() {
    return id;
  }

  public String getExpenseNo() {
    return expenseNo;
  }

  public CompanyExpenseKind getKind() {
    return kind;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getGlHint() {
    return glHint;
  }

  public String getDescription() {
    return description;
  }

  public Money amount() {
    return Money.ofMinor(amountMinor, Currency.getInstance(currency));
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public CompanyExpenseStatus getStatus() {
    return status;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }

  public UUID getVoidJournalEntryId() {
    return voidJournalEntryId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
