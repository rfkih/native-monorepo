package id.co.nativeapp.finance.labor.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payroll_run_ledger} control/state row (#23) — one per {@code (company_id, period,
 * run_seq)}. Drives RECONCILIATION (the running {@code allocated_sum_minor} of the run's buckets vs
 * the {@code control_total_minor} carried on {@code PayrollPosted}) and SUPERSESSION (a higher
 * {@code run_seq} supersedes lower ones; the prior row flips to {@link
 * PayrollRunState#SUPERSEDED}).
 *
 * <p>Extends {@link Auditable} (rule 4) and is under FORCE RLS (rule 5) — it is per-tenant control
 * data, so a tenant only ever sees its own runs. Money stays minor units + currency, never a float
 * (rule 8). Unlike the append-only {@code ledger_posting}, this control row IS mutated in place
 * (accumulate the sum, set the control total, transition state) — the LEDGER never mutates; only
 * this control table does, which is what keeps supersession auditable and the ledger immutable.
 */
@Entity
@Table(name = "payroll_run_ledger")
public class PayrollRunLedger extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payroll_run_id", nullable = false, updatable = false)
  private UUID payrollRunId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "run_seq", nullable = false, updatable = false)
  private int runSeq;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private PayrollRunState state;

  @Column(name = "allocated_sum_minor", nullable = false)
  private long allocatedSumMinor;

  @Column(name = "control_total_minor")
  private Long controlTotalMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "uses_illustrative_rules", nullable = false)
  private boolean usesIllustrativeRules;

  protected PayrollRunLedger() {
    // for JPA
  }

  /**
   * Opens a fresh {@link PayrollRunState#PENDING} control row for a run, seeded from the first
   * bucket (or the {@code PayrollPosted}) seen for it. The owning tenant is stamped via {@link
   * #setCompanyId} by the caller from the bound {@link id.co.nativeapp.tenant.TenantContext}.
   *
   * @param payrollRunId the owning payroll run
   * @param period the run's authoritative accounting period {@code YYYY-MM}
   * @param runSeq the run sequence (the supersession signal)
   * @param currency the run's base currency (ISO-4217)
   * @param usesIllustrativeRules whether the run is illustrative-placeholder-derived
   */
  public PayrollRunLedger(
      UUID payrollRunId,
      String period,
      int runSeq,
      String currency,
      boolean usesIllustrativeRules) {
    this.id = UUID.randomUUID();
    this.payrollRunId = Objects.requireNonNull(payrollRunId, "payrollRunId");
    this.period = Objects.requireNonNull(period, "period");
    this.runSeq = runSeq;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.usesIllustrativeRules = usesIllustrativeRules;
    this.state = PayrollRunState.PENDING;
    this.allocatedSumMinor = 0L;
  }

  /**
   * Adds one bucket's amount onto the running allocated sum (reconciliation accumulator). Carries
   * the illustrative flag forward monotonically (once any illustrative bucket lands the run is
   * flagged).
   *
   * <p>A currency-divergent bucket never reaches this method: the caller pre-screens it via {@code
   * divergentPeriodCurrency} and routes the run to the terminal {@link
   * PayrollRunState#CURRENCY_MISMATCH} before accumulating, so this always sees the run's
   * established currency. {@link id.co.nativeapp.money.MismatchedCurrencyException} therefore
   * remains the general contract of {@link Money#plus} but is not expected here in practice.
   */
  public void accumulate(Money bucketAmount, boolean bucketIllustrative) {
    Money current = Money.ofMinor(allocatedSumMinor, currency.strip());
    this.allocatedSumMinor = current.plus(bucketAmount).amountMinor();
    this.usesIllustrativeRules = this.usesIllustrativeRules || bucketIllustrative;
  }

  /** Records the {@code PayrollPosted} labor control total (minor units) for reconciliation. */
  public void recordControlTotal(Money controlTotal, boolean runIllustrative) {
    this.controlTotalMinor = controlTotal.amountMinor();
    this.usesIllustrativeRules = this.usesIllustrativeRules || runIllustrative;
  }

  /**
   * Transitions the run to a new lifecycle {@link PayrollRunState}.
   *
   * <p>{@link PayrollRunState#SUPERSEDED} and {@link PayrollRunState#CURRENCY_MISMATCH} are
   * TERMINAL (#23): once entered, a later {@code PayrollPosted} or bucket must NOT clobber that
   * terminal state back to {@code RECONCILED}/{@code RECONCILE_FAILED}. For {@code SUPERSEDED} that
   * would display a reversed run as reconciled; for {@code CURRENCY_MISMATCH} a later in-currency
   * bucket completing the control sum would mask that a divergent-currency cost was observed and
   * dropped. A transition AWAY from either terminal state is therefore a no-op (idempotent
   * re-application of the same terminal state is fine).
   */
  public void transitionTo(PayrollRunState newState) {
    Objects.requireNonNull(newState, "newState");
    if (isTerminal(this.state) && newState != this.state) {
      return;
    }
    this.state = newState;
  }

  private static boolean isTerminal(PayrollRunState state) {
    return state == PayrollRunState.SUPERSEDED || state == PayrollRunState.CURRENCY_MISMATCH;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPayrollRunId() {
    return payrollRunId;
  }

  public String getPeriod() {
    return period;
  }

  public int getRunSeq() {
    return runSeq;
  }

  public PayrollRunState getState() {
    return state;
  }

  /** The running sum of this run's bucket amounts as {@link Money}. */
  public Money allocatedSum() {
    return Money.ofMinor(allocatedSumMinor, currency.strip());
  }

  /**
   * The run's established ISO-4217 currency code (trimmed of the {@code CHAR(3)} padding) — the
   * single currency every bucket of the run must be in (no FX in scope, #23). A divergent-currency
   * bucket is routed to the terminal {@link PayrollRunState#CURRENCY_MISMATCH} rather than silently
   * splitting the P&amp;L into a second-currency row.
   */
  public String currencyCode() {
    return currency.strip();
  }

  /** The recorded control total as {@link Money}, or {@code null} until {@code PayrollPosted}. */
  public Money controlTotal() {
    return controlTotalMinor == null ? null : Money.ofMinor(controlTotalMinor, currency.strip());
  }

  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }
}
