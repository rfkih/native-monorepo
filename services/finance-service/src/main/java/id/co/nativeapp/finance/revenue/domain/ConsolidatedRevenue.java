package id.co.nativeapp.finance.revenue.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code consolidated_revenue} read model — one incrementally-accumulated row per {@code
 * (company_id, period, currency)}. As each {@link LedgerPosting} lands, its amount is added to the
 * matching accumulator in the SAME transaction, so the read model is always consistent with the
 * ledger. This is the projection the dashboard query API serves (CQRS: post on write, aggregate on
 * read — here the aggregation is maintained eagerly so the read is a single-row lookup).
 *
 * <p>It extends {@link Auditable} and is under RLS: it is an <em>application-maintained</em> read
 * model (updated transactionally by this service), not a Debezium-CDC-derived projection, so the
 * rule-4 Auditable and rule-5 RLS guarantees apply uniformly — a tenant only ever sees its own
 * accumulators.
 *
 * <p>The total is a {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217
 * currency, never a float), persisted via {@link MoneyEmbeddable} as {@code total_minor BIGINT} +
 * {@code currency CHAR(3)}. {@link #add(Money)} uses {@code Money} arithmetic, which refuses to mix
 * currencies — a guard against accidentally summing across currencies (there is no FX in M1.5).
 */
@Entity
@Table(name = "consolidated_revenue")
public class ConsolidatedRevenue extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  // The Money pair maps to total_minor + currency on this table; MoneyEmbeddable's default
  // amount_minor column is remapped to total_minor here (the currency column name is unchanged).
  @Embedded
  @AttributeOverride(name = "amountMinor", column = @Column(name = "total_minor", nullable = false))
  private MoneyEmbeddable total;

  protected ConsolidatedRevenue() {
    // for JPA
  }

  /**
   * Opens a new accumulator for a tenant + period + currency, seeded at zero.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @param currencyCode the ISO-4217 currency this accumulator totals (the row's currency
   *     dimension)
   */
  public ConsolidatedRevenue(String period, String currencyCode) {
    this.id = UUID.randomUUID();
    this.period = Objects.requireNonNull(period, "period");
    this.total =
        MoneyEmbeddable.of(Money.ofMinor(0L, Objects.requireNonNull(currencyCode, "currencyCode")));
  }

  /**
   * Adds a posting amount to the running total. Uses {@code Money} arithmetic, which throws if the
   * amount's currency differs from this accumulator's currency — the rows are keyed by currency, so
   * a mismatch would be a bug (no implicit FX in M1.5).
   */
  public void add(Money amount) {
    Objects.requireNonNull(amount, "amount");
    this.total = MoneyEmbeddable.of(this.total.toMoney().plus(amount));
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  /** The accumulated revenue total as a {@link Money} value. */
  public Money getTotal() {
    return total.toMoney();
  }
}
