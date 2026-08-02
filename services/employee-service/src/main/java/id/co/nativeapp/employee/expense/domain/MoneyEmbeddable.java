package id.co.nativeapp.employee.expense.domain;

import id.co.nativeapp.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA {@link Embeddable} persistence shape for {@code libs/money} {@link Money}: a {@code long}
 * amount in the currency's minor units plus the ISO-4217 currency code. <strong>Money is never a
 * float</strong> (rule 8) — this maps to {@code amount_minor BIGINT} + {@code amount_currency
 * CHAR(3)}, carrying no floating-point field.
 *
 * <p>A local copy of the payroll feature's identical embeddable (feature cohesion — CODE-STRUCTURE
 * §2 keeps an aggregate's classes, including its small value types, moving together rather than
 * reaching across feature packages). Used for {@code expense_claim.amount}, which is deliberately
 * PLAIN (not PII-encrypted like {@code payslip_line}): a claim amount is manager-visible
 * operational data (ADR 0030), not individual compensation.
 */
@Embeddable
public class MoneyEmbeddable {

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "amount_currency", nullable = false, length = 3)
  private String currency;

  protected MoneyEmbeddable() {
    // for JPA
  }

  private MoneyEmbeddable(long amountMinor, String currency) {
    this.amountMinor = amountMinor;
    this.currency = currency;
  }

  /** Projects a {@link Money} value down to its two persistent columns. */
  public static MoneyEmbeddable of(Money money) {
    Objects.requireNonNull(money, "money");
    return new MoneyEmbeddable(money.amountMinor(), money.currency().getCurrencyCode());
  }

  /** Reconstructs the {@link Money} value (re-validating the ISO-4217 currency). */
  public Money toMoney() {
    return Money.ofMinor(amountMinor, currency.strip());
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency.strip();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MoneyEmbeddable other)) {
      return false;
    }
    return amountMinor == other.amountMinor && Objects.equals(currency, other.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(amountMinor, currency);
  }
}
