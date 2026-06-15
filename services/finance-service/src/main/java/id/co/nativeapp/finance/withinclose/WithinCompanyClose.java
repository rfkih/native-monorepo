package id.co.nativeapp.finance.withinclose;

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
 * The {@code within_company_close} aggregate (P3d SEAM 4a) — the record that a company FINALISED
 * its own period: the within-company analogue of {@link
 * id.co.nativeapp.finance.consolidation.ConsolidationSummary} (the GROUP close). Creating it is
 * what makes the close emit {@code ConsolidationClosed(group_id = null)} and a {@code
 * TrialBalancePublished} per group the company belongs to — all atomic with this row (rule 3).
 *
 * <p><strong>Idempotency.</strong> A {@code UNIQUE (company_id, period)} guards the close: a second
 * close of an already-closed {@code (company, period)} finds the row and is a clean no-op that
 * re-emits NOTHING. So a re-close cannot double-fire the loop-closing events.
 *
 * <p>It extends {@link Auditable}, so {@code company_id} (the closing company, the tenant) is
 * present and the row is covered by the {@code within_company_close} RLS policy (rule 4 + rule 5).
 */
@Entity
@Table(name = "within_company_close")
public class WithinCompanyClose extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "base_currency", nullable = false, updatable = false, length = 3)
  private String baseCurrency;

  @Column(name = "reconciled", nullable = false, updatable = false)
  private boolean reconciled;

  @Column(name = "uses_illustrative_rules", nullable = false, updatable = false)
  private boolean usesIllustrativeRules;

  protected WithinCompanyClose() {
    // for JPA
  }

  /**
   * Creates a within-company close record for {@code (the bound company, period)}.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @param baseCurrency the company's base (functional) ISO-4217 currency
   * @param reconciled whether the trial balance reconciled (always true — the balancing equity line
   *     makes the signed double-entry residual zero)
   * @param usesIllustrativeRules sticky-OR from any illustrative-derived posting in the period
   */
  public WithinCompanyClose(
      String period, String baseCurrency, boolean reconciled, boolean usesIllustrativeRules) {
    this.id = UUID.randomUUID();
    this.period = Objects.requireNonNull(period, "period");
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency").strip();
    this.reconciled = reconciled;
    this.usesIllustrativeRules = usesIllustrativeRules;
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  /** The company's base currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getBaseCurrency() {
    return baseCurrency.strip();
  }

  public boolean isReconciled() {
    return reconciled;
  }

  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }
}
