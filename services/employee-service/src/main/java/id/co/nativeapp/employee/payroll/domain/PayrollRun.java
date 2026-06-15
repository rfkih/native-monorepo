package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payroll_run} aggregate (design §1/§5): one run per (company_id, period, run_seq). It
 * owns the run lifecycle ({@link RunStatus}), the FROZEN {@code rule_version_set_json} that makes
 * the run reproducible (HR-7), the runtime {@code uses_illustrative_rules} flag, and the
 * company-level money TOTALS (NOT individual PII — safe to event).
 *
 * <p>A re-run of the same period is a NEW {@code run_seq} (a correction), guarded by a UNIQUE
 * (company_id, period, run_seq); only a {@code CALCULATED -> POSTED} transition emits, so a retried
 * post cannot double-emit. Extends {@link Auditable} (rule 4); under the {@code payroll_run} RLS
 * policy (rule 5).
 */
@Entity
@Table(name = "payroll_run")
public class PayrollRun extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, length = 7)
  private String period;

  @Column(name = "run_seq", nullable = false)
  private int runSeq;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private RunStatus status;

  /**
   * Frozen map {@code rule_key -> {statutory_rule_id, rule_version}} resolved at run start; makes a
   * re-run byte-identical (HR-7). JSON.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rule_version_set_json", nullable = false, columnDefinition = "jsonb")
  private String ruleVersionSetJson;

  /**
   * The runtime illustrative flag — true if ANY resolved statutory rule is an illustrative
   * placeholder. Rides on both published events.
   */
  @Column(name = "uses_illustrative_rules", nullable = false)
  private boolean usesIllustrativeRules;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amountMinor", column = @Column(name = "gross_total_minor")),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "gross_total_currency", length = 3))
  })
  private MoneyEmbeddable grossTotal;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "employee_deduction_total_minor")),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "employee_deduction_total_currency", length = 3))
  })
  private MoneyEmbeddable employeeDeductionTotal;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "employer_contribution_total_minor")),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "employer_contribution_total_currency", length = 3))
  })
  private MoneyEmbeddable employerContributionTotal;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amountMinor", column = @Column(name = "net_total_minor")),
    @AttributeOverride(name = "currency", column = @Column(name = "net_total_currency", length = 3))
  })
  private MoneyEmbeddable netTotal;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "base_currency", nullable = false, length = 3)
  private String baseCurrency;

  /** The PeriodSealed completeness ledger (which expected sources sealed). JSON. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sealed_sources_json", nullable = false, columnDefinition = "jsonb")
  private String sealedSourcesJson;

  @Column(name = "posted_at")
  private Instant postedAt;

  protected PayrollRun() {
    // for JPA
  }

  /** Creates a DRAFT run with zeroed totals in the base currency. */
  public PayrollRun(String period, int runSeq, String baseCurrency) {
    this.id = UUID.randomUUID();
    this.period = Objects.requireNonNull(period, "period");
    this.runSeq = runSeq;
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
    this.status = RunStatus.DRAFT;
    this.ruleVersionSetJson = "{}";
    this.usesIllustrativeRules = false;
    this.sealedSourcesJson = "[]";
    Money zero = Money.ofMinor(0L, baseCurrency);
    this.grossTotal = MoneyEmbeddable.of(zero);
    this.employeeDeductionTotal = MoneyEmbeddable.of(zero);
    this.employerContributionTotal = MoneyEmbeddable.of(zero);
    this.netTotal = MoneyEmbeddable.of(zero);
  }

  /** Records the frozen rule-version set + illustrative flag resolved at run start. */
  public void freezeRuleVersionSet(String ruleVersionSetJson, boolean usesIllustrativeRules) {
    this.ruleVersionSetJson = Objects.requireNonNull(ruleVersionSetJson, "ruleVersionSetJson");
    this.usesIllustrativeRules = usesIllustrativeRules;
  }

  public void recordSealedSources(String sealedSourcesJson) {
    this.sealedSourcesJson = Objects.requireNonNull(sealedSourcesJson, "sealedSourcesJson");
  }

  /** Sets the four company-level totals (computed in-memory by the engine). */
  public void setTotals(
      Money gross, Money employeeDeductions, Money employerContributions, Money net) {
    this.grossTotal = MoneyEmbeddable.of(gross);
    this.employeeDeductionTotal = MoneyEmbeddable.of(employeeDeductions);
    this.employerContributionTotal = MoneyEmbeddable.of(employerContributions);
    this.netTotal = MoneyEmbeddable.of(net);
  }

  public void markGatePending() {
    this.status = RunStatus.SEALED_GATE_PENDING;
  }

  public void markCalculating() {
    this.status = RunStatus.CALCULATING;
  }

  public void markCalculated() {
    this.status = RunStatus.CALCULATED;
  }

  /** Transitions to POSTED, stamping posted_at. Only valid from CALCULATED (caller guards). */
  public void markPosted(Instant postedAt) {
    this.status = RunStatus.POSTED;
    this.postedAt = Objects.requireNonNull(postedAt, "postedAt");
  }

  public void markFailed() {
    this.status = RunStatus.FAILED;
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  public int getRunSeq() {
    return runSeq;
  }

  public RunStatus getStatus() {
    return status;
  }

  public String getRuleVersionSetJson() {
    return ruleVersionSetJson;
  }

  public boolean usesIllustrativeRules() {
    return usesIllustrativeRules;
  }

  public Money getGrossTotal() {
    return grossTotal.toMoney();
  }

  public Money getEmployeeDeductionTotal() {
    return employeeDeductionTotal.toMoney();
  }

  public Money getEmployerContributionTotal() {
    return employerContributionTotal.toMoney();
  }

  public Money getNetTotal() {
    return netTotal.toMoney();
  }

  public String getBaseCurrency() {
    return baseCurrency.strip();
  }

  public String getSealedSourcesJson() {
    return sealedSourcesJson;
  }

  public Instant getPostedAt() {
    return postedAt;
  }

  @Override
  public String toString() {
    return "PayrollRun[id="
        + id
        + ", period="
        + period
        + ", runSeq="
        + runSeq
        + ", status="
        + status
        + "]";
  }
}
