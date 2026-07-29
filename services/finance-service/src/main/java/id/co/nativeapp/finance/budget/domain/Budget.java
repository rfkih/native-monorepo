package id.co.nativeapp.finance.budget.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code budget} aggregate (Phase 5, ADR 0019) — a named set of planned amounts for one month.
 * PLANNING DATA: a budget never posts to the GL; the budget-vs-actual report compares its {@link
 * BudgetLine}s against the period's GL actuals. The parent of a parent+child pair, mirroring
 * {@code Bill}/{@code BillLine}.
 *
 * <p>Extends {@link Auditable}, so it carries the six audit + tenancy columns and is covered by the
 * {@code budget} RLS policy (V33), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "budget")
public class Budget extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected Budget() {
    // for JPA
  }

  /**
   * Creates a budget for {@code (name, period)} in the given currency.
   *
   * @param name a non-blank display name
   * @param period the accounting period {@code YYYY-MM} this budget plans for
   * @param currencyCode the ISO-4217 currency of the planned amounts
   * @throws IllegalArgumentException if {@code name} is blank or {@code currencyCode} is not ISO-4217
   */
  public static Budget create(String name, String period, String currencyCode) {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Budget budget = new Budget();
    budget.id = UUID.randomUUID();
    budget.name = requireName(name);
    budget.period = period;
    budget.currency = Currency.getInstance(currencyCode).getCurrencyCode();
    return budget;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("budget name must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getPeriod() {
    return period;
  }

  /** The budget currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getCurrency() {
    return currency.strip();
  }
}
