package id.co.nativeapp.finance.budget.domain;

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
 * One line of a {@link Budget} (Phase 5) — the planned amount for a single account, a non-negative
 * target in the account's normal direction (e.g. a revenue target, an expense budget). The child of
 * the {@code Bill}/{@code BillLine}-style parent+child pair.
 *
 * <p>Extends {@link Auditable}: carries the six audit + tenancy columns, covered by the {@code
 * budget_line} RLS policy (V33), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "budget_line")
public class BudgetLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "budget_id", nullable = false, updatable = false)
  private UUID budgetId;

  /** FK → {@code chart_of_account.account_code} (enforced in the schema). */
  @Column(name = "account_code", nullable = false, updatable = false, length = 32)
  private String accountCode;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected BudgetLine() {
    // for JPA
  }

  /**
   * Creates a budget line planning {@code amount} for {@code accountCode}.
   *
   * @throws IllegalArgumentException if {@code amount} is negative (a planned amount is a target)
   */
  public static BudgetLine of(UUID budgetId, String accountCode, Money amount) {
    Objects.requireNonNull(budgetId, "budgetId");
    Objects.requireNonNull(accountCode, "accountCode");
    Objects.requireNonNull(amount, "amount");
    if (amount.amountMinor() < 0L) {
      throw new IllegalArgumentException("budget line amount must not be negative: " + amount);
    }
    BudgetLine line = new BudgetLine();
    line.id = UUID.randomUUID();
    line.budgetId = budgetId;
    line.accountCode = accountCode;
    line.amountMinor = amount.amountMinor();
    line.currency = amount.currency().getCurrencyCode();
    return line;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBudgetId() {
    return budgetId;
  }

  public String getAccountCode() {
    return accountCode;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency.strip();
  }
}
