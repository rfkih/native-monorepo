package id.co.nativeapp.barbershop.catalog.domain;

import id.co.nativeapp.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA {@link Embeddable} persistence shape for {@code libs/money} {@link Money}: a {@code long}
 * amount in the currency's minor units plus the ISO-4217 currency code. <strong>Money is never a
 * float</strong> (CLAUDE.md rule 8) — this maps to {@code amount_minor BIGINT} + {@code currency
 * CHAR(3)}.
 *
 * <p>The domain works entirely in {@link Money}; this embeddable exists only at the persistence
 * boundary. {@link #toMoney()} reconstructs the value type (which re-validates the currency via
 * {@link Money#ofMinor(long, String)}), and {@link #of(Money)} projects a {@link Money} back down
 * to its two columns. Hibernate requires the no-arg constructor and mutable fields, hence the
 * protected ctor and setters; application code should prefer {@link #of(Money)}.
 *
 * <p><strong>Placement note (deliberate difference from carwash-service).</strong> carwash-service
 * anchors its shared {@code MoneyEmbeddable} in its legacy {@code wash.domain} package even though
 * {@code catalog}, {@code payment}, and {@code ticket} all reach across to reuse it. barbershop-
 * service has no such legacy feature (ADR 0024: the ticket flow is the only revenue path), so this
 * type is anchored here instead — {@code catalog} is the first feature that needs a priced Money
 * column — and {@code payment}/{@code ticket} depend on it, the same cross-feature domain-type reuse
 * carwash already establishes as the house pattern (one {@code @Embeddable} per service is enough).
 */
@Embeddable
public class MoneyEmbeddable {

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  /**
   * ISO-4217 alphabetic code, e.g. {@code "IDR"} / {@code "USD"}. Mapped as a fixed-width {@code
   * CHAR(3)} (PostgreSQL {@code bpchar}); {@link SqlTypes#CHAR} makes Hibernate's {@code
   * ddl-auto=validate} agree with the migrated column type. PostgreSQL space-pads {@code CHAR} on
   * read, so {@link #toMoney()} strips before re-validating the code.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
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

  /**
   * Reconstructs the {@link Money} value type, re-validating the currency code through {@link
   * Money#ofMinor(long, String)} (ISO-4217).
   */
  public Money toMoney() {
    return Money.ofMinor(amountMinor, currency.strip());
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
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
