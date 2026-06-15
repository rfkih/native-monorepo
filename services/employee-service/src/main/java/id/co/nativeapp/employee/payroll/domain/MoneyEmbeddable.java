package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA {@link Embeddable} persistence shape for {@code libs/money} {@link Money}: a {@code long}
 * amount in the currency's minor units plus the ISO-4217 currency code. <strong>Money is never a
 * float</strong> (rule 8) — this maps to {@code <col>_minor BIGINT} + {@code <col>_currency
 * CHAR(3)}, and carries no floating-point field.
 *
 * <p>Used ONLY for NON-PII aggregates in payroll: {@code payroll_run} company-level totals and
 * {@code labor_cost_allocation} outlet/GL cost-center buckets. Individual salary amounts (base pay,
 * payslip-line amounts) are NOT stored this way — they are ciphertext via {@link MoneyPiiConverter}
 * (rule 6).
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
