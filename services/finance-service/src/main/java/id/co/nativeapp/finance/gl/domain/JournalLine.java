package id.co.nativeapp.finance.gl.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of a {@link JournalEntry} — the row-level double-entry representation.
 *
 * <p><strong>Single-sided invariant.</strong> Exactly one of {@code debit_minor} / {@code
 * credit_minor} is zero and the other is strictly positive, enforced by the SQL {@code CHECK
 * (debit_minor=0)<>(credit_minor=0)} (V13) and by {@link JournalEntry#balanced}. A line carrying
 * both or neither is rejected before it can reach the database.
 *
 * <p><strong>Two longs + one currency.</strong> We deliberately do NOT use two {@code
 * MoneyEmbeddable} columns here. Embedding two {@code MoneyEmbeddable} instances would produce two
 * columns both named {@code currency}, causing a Hibernate duplicate-column clash. Instead we store
 * {@code debit_minor}, {@code credit_minor}, and a single {@code currency} column; {@link
 * #debitMoney()} and {@link #creditMoney()} reconstruct the {@link Money} value on demand.
 *
 * <p>Extends {@link Auditable}: every Native table carries the six audit + tenancy columns (rule
 * 4), and this table is under {@code FORCE ROW LEVEL SECURITY} scoped to {@code app.current_tenant}
 * (rule 5, V13).
 */
@Entity
@Table(name = "journal_line")
public class JournalLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "entry_id", nullable = false, updatable = false)
  private UUID entryId;

  @Column(name = "line_no", nullable = false, updatable = false)
  private int lineNo;

  /** FK → {@code chart_of_account.account_code}. */
  @Column(name = "account_code", nullable = false, updatable = false, length = 32)
  private String accountCode;

  /**
   * Debit amount in the entry's currency minor units. Exactly one of this and {@code credit_minor}
   * is zero; the other is strictly positive (enforced by SQL CHECK and the factory).
   */
  @Column(name = "debit_minor", nullable = false, updatable = false)
  private long debitMinor;

  /**
   * Credit amount in the entry's currency minor units. Exactly one of this and {@code debit_minor}
   * is zero; the other is strictly positive (enforced by SQL CHECK and the factory).
   */
  @Column(name = "credit_minor", nullable = false, updatable = false)
  private long creditMinor;

  /**
   * ISO-4217 currency code, shared across the line's debit/credit columns. Must equal the owning
   * entry's currency.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected JournalLine() {
    // for JPA
  }

  /**
   * Factory: builds a single-sided debit line.
   *
   * @param entryId the owning journal entry id
   * @param lineNo the ordering within the entry (1-based)
   * @param accountCode the resolved {@code chart_of_account.account_code}
   * @param debitAmount the debit money (must be positive; currency must match the entry currency)
   */
  public static JournalLine debit(UUID entryId, int lineNo, String accountCode, Money debitAmount) {
    Objects.requireNonNull(debitAmount, "debitAmount");
    if (debitAmount.amountMinor() <= 0) {
      throw new UnbalancedJournalException(
          "Debit line amount must be strictly positive: " + debitAmount);
    }
    JournalLine line = new JournalLine();
    line.id = UUID.randomUUID();
    line.entryId = Objects.requireNonNull(entryId, "entryId");
    line.lineNo = lineNo;
    line.accountCode = Objects.requireNonNull(accountCode, "accountCode");
    line.debitMinor = debitAmount.amountMinor();
    line.creditMinor = 0L;
    line.currency = debitAmount.currency().getCurrencyCode();
    return line;
  }

  /**
   * Factory: builds a single-sided credit line.
   *
   * @param entryId the owning journal entry id
   * @param lineNo the ordering within the entry (1-based)
   * @param accountCode the resolved {@code chart_of_account.account_code}
   * @param creditAmount the credit money (must be positive; currency must match the entry currency)
   */
  public static JournalLine credit(
      UUID entryId, int lineNo, String accountCode, Money creditAmount) {
    Objects.requireNonNull(creditAmount, "creditAmount");
    if (creditAmount.amountMinor() <= 0) {
      throw new UnbalancedJournalException(
          "Credit line amount must be strictly positive: " + creditAmount);
    }
    JournalLine line = new JournalLine();
    line.id = UUID.randomUUID();
    line.entryId = Objects.requireNonNull(entryId, "entryId");
    line.lineNo = lineNo;
    line.accountCode = Objects.requireNonNull(accountCode, "accountCode");
    line.debitMinor = 0L;
    line.creditMinor = creditAmount.amountMinor();
    line.currency = creditAmount.currency().getCurrencyCode();
    return line;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEntryId() {
    return entryId;
  }

  public int getLineNo() {
    return lineNo;
  }

  public String getAccountCode() {
    return accountCode;
  }

  public long getDebitMinor() {
    return debitMinor;
  }

  public long getCreditMinor() {
    return creditMinor;
  }

  public String getCurrency() {
    return currency;
  }

  /**
   * Reconstructs the debit {@link Money} (may be zero when this is a credit line). Never a float.
   */
  public Money debitMoney() {
    return Money.ofMinor(debitMinor, currency.strip());
  }

  /**
   * Reconstructs the credit {@link Money} (may be zero when this is a debit line). Never a float.
   */
  public Money creditMoney() {
    return Money.ofMinor(creditMinor, currency.strip());
  }
}
