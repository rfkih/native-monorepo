package id.co.nativeapp.entitlement.entitlement.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code billing_line} aggregate — one billed line per {@code (company_id, module_key,
 * period)}: what a company is charged for a module in a billing period.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns and is
 * covered by the {@code billing_line} RLS policy in the Flyway baseline (rule 4 + rule 5).
 *
 * <p>The monetary amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units +
 * ISO-4217 currency, never a float), persisted via {@link MoneyEmbeddable} as {@code amount_minor
 * BIGINT} + {@code currency CHAR(3)}. The full billing engine (rates, proration) is out of scope
 * here; this aggregate models the owned shape so the table exists with the rule-8 Money columns and
 * RLS, ready for the billing gate the entitlement-check library is named for.
 */
@Entity
@Table(name = "billing_line")
public class BillingLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "module_key", nullable = false, updatable = false, length = 64)
  private String moduleKey;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Embedded private MoneyEmbeddable amount;

  protected BillingLine() {
    // for JPA
  }

  /**
   * Creates a billing line.
   *
   * @param moduleKey the module being billed (validated against {@code module_catalog})
   * @param period the billing period {@code YYYY-MM}
   * @param amount the charged amount as {@link Money} (never a float)
   */
  public BillingLine(String moduleKey, String period, Money amount) {
    this.id = UUID.randomUUID();
    this.moduleKey = Objects.requireNonNull(moduleKey, "moduleKey");
    this.period = Objects.requireNonNull(period, "period");
    this.amount = MoneyEmbeddable.of(amount);
  }

  public UUID getId() {
    return id;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public String getPeriod() {
    return period;
  }

  /** The charged amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }
}
