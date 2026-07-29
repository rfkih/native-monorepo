package id.co.nativeapp.finance.bank.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code bank_statement_line} aggregate — one line from a bank statement, reconciled to the GL
 * (Phase 3 bank reconciliation, ADR 0016; V29).
 *
 * <p><strong>Signed amount.</strong> {@code amountMinor} is SIGNED: positive = deposit (money into
 * the account), negative = withdrawal — stored as BIGINT minor units plus one {@code currency}
 * column, never a float (rule 8).
 *
 * <p><strong>Reconciliation.</strong> {@link #reconcile} is the ONLY mutator: it guards {@code
 * status == UNRECONCILED} (else {@link ReconciliationStateException}, HTTP 409 — no
 * double-reconcile) and transitions to {@link StatementLineStatus#RECONCILED}, stamping the chosen
 * {@link ReconciliationCategory} and the balanced transfer {@code JournalEntry} id that {@code
 * ReconciliationWriter} posts in the same transaction, immediately before this call.
 *
 * <p>Extends {@link Auditable}; under the {@code bank_statement_line} RLS policy (V29).
 */
@Entity
@Table(name = "bank_statement_line")
public class BankStatementLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "bank_account_id", nullable = false, updatable = false)
  private UUID bankAccountId;

  @Column(name = "line_date", nullable = false, updatable = false)
  private LocalDate lineDate;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "reference", length = 128)
  private String reference;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private StatementLineStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "reconciled_category", length = 16)
  private ReconciliationCategory reconciledCategory;

  @Column(name = "journal_entry_id")
  private UUID journalEntryId;

  protected BankStatementLine() {
    // for JPA
  }

  /**
   * Creates an UNRECONCILED statement line for import. {@code amountMinor} must be non-zero (the
   * SQL CHECK mirrors this — a statement line always represents a real movement); {@code
   * description}/{@code reference} are optional (blank normalised to {@code null}).
   *
   * @throws IllegalArgumentException if {@code amountMinor} is zero, or {@code currencyCode} is not
   *     a valid ISO-4217 code
   */
  public static BankStatementLine of(
      UUID bankAccountId,
      LocalDate lineDate,
      long amountMinor,
      String currencyCode,
      String description,
      String reference) {
    Objects.requireNonNull(bankAccountId, "bankAccountId");
    Objects.requireNonNull(lineDate, "lineDate");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (amountMinor == 0L) {
      throw new IllegalArgumentException("statement line amount must not be zero");
    }
    BankStatementLine line = new BankStatementLine();
    line.id = UUID.randomUUID();
    line.bankAccountId = bankAccountId;
    line.lineDate = lineDate;
    line.amountMinor = amountMinor;
    line.currency = Currency.getInstance(currencyCode).getCurrencyCode();
    line.description = blankToNull(description);
    line.reference = blankToNull(reference);
    line.status = StatementLineStatus.UNRECONCILED;
    return line;
  }

  /**
   * Reconciles this line against {@code category}, linking the balanced transfer {@code
   * journalEntryId} that {@code ReconciliationWriter} posted in the same transaction immediately
   * before this call.
   *
   * @throws ReconciliationStateException if the line is not UNRECONCILED (no double-reconcile)
   */
  public void reconcile(ReconciliationCategory category, UUID journalEntryId) {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(journalEntryId, "journalEntryId");
    if (status != StatementLineStatus.UNRECONCILED) {
      throw new ReconciliationStateException(
          "only an UNRECONCILED line can be reconciled; current status=" + status);
    }
    this.status = StatementLineStatus.RECONCILED;
    this.reconciledCategory = category;
    this.journalEntryId = journalEntryId;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBankAccountId() {
    return bankAccountId;
  }

  public LocalDate getLineDate() {
    return lineDate;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency.strip();
  }

  public String getDescription() {
    return description;
  }

  public String getReference() {
    return reference;
  }

  public StatementLineStatus getStatus() {
    return status;
  }

  public ReconciliationCategory getReconciledCategory() {
    return reconciledCategory;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }
}
