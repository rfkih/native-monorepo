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
 * run_type, run_seq)} (ADR 0032, Track P phase P4 re-keyed the row onto {@code run_type} too; the
 * DEFAULT {@code 'REGULAR'} keeps every pre-P4 row and behaviour byte-identical). Drives
 * RECONCILIATION (the running {@code allocated_sum_minor} of the run's buckets vs the {@code
 * control_total_minor} carried on {@code PayrollPosted}) and SUPERSESSION (a higher {@code run_seq}
 * of the SAME {@code run_type} supersedes lower ones; the prior row flips to {@link
 * PayrollRunState#SUPERSEDED}).
 *
 * <p><strong>Two independent lifecycles on ONE row (ADR 0032).</strong> {@link #state} stays the
 * pre-existing LaborCostPostingWriter/PayrollReconciliationWriter reconciliation lifecycle; {@link
 * #liabilityState} is the LIABILITY dimension's OWN lifecycle, tracked separately (see {@link
 * LiabilityState}) so a cross-writer coordination gap cannot hide a prior run's un-reversed
 * liability entry behind an already-{@code SUPERSEDED} {@link #state}.
 *
 * <p>Extends {@link Auditable} (rule 4) and is under FORCE RLS (rule 5) — it is per-tenant control
 * data, so a tenant only ever sees its own runs. Money stays minor units + currency, never a float
 * (rule 8). Unlike the append-only {@code ledger_posting}/{@code journal_entry}, this control row
 * IS mutated in place (accumulate the sum, set the control total, transition state) — the LEDGER
 * never mutates; only this control table does, which is what keeps supersession auditable and the
 * ledger immutable.
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

  /**
   * The payroll run type ({@code REGULAR} today; {@code THR} lands at Track P phase P8, ADR 0034).
   * Supersession is scoped to {@code (company_id, period, run_type, run_seq)} — ADR 0032, Track P
   * phase P4 — so a future THR run never supersedes a REGULAR run for the same period.
   */
  @Column(name = "run_type", nullable = false, updatable = false, length = 16)
  private String runType;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private PayrollRunState state;

  /**
   * The journal_entry id of this run's LIABILITY recognition entry (ADR 0032, Track P phase P4), or
   * {@code null} until {@code PayrollLiabilitiesPosted} lands. Tracked independently from the
   * pre-existing {@code state}/reconciliation lifecycle above — see {@link LiabilityState}.
   */
  @Column(name = "liability_entry_id")
  private UUID liabilityEntryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "liability_state", length = 32)
  private LiabilityState liabilityState;

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
   * @param runType the payroll run type ({@code REGULAR} today — ADR 0032, Track P phase P4)
   * @param currency the run's base currency (ISO-4217)
   * @param usesIllustrativeRules whether the run is illustrative-placeholder-derived
   */
  public PayrollRunLedger(
      UUID payrollRunId,
      String period,
      int runSeq,
      String runType,
      String currency,
      boolean usesIllustrativeRules) {
    this.id = UUID.randomUUID();
    this.payrollRunId = Objects.requireNonNull(payrollRunId, "payrollRunId");
    this.period = Objects.requireNonNull(period, "period");
    this.runSeq = runSeq;
    this.runType = Objects.requireNonNull(runType, "runType");
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

  /**
   * Records the {@code PayrollPosted} labor control total (minor units) for reconciliation.
   *
   * <p>The control total is stored as bare minor units and later reconstructed in the run row's
   * established {@link #currency} (see {@link #controlTotal()}). That reconstruction is only sound
   * if the incoming total is in the SAME currency, so this asserts the invariant explicitly (#35).
   * Today it is benign — {@code base_currency} seeds both the run row and {@code PayrollPosted}
   * identically — but if a future change let a divergent-currency total in, silently storing its
   * minor units against this row's currency would misstate the control. We fail loudly instead of
   * dropping the currency.
   *
   * @throws IllegalArgumentException if the control total's currency differs from the run's
   *     established currency
   */
  public void recordControlTotal(Money controlTotal, boolean runIllustrative) {
    String controlCurrency = controlTotal.currency().getCurrencyCode();
    if (!currency.strip().equals(controlCurrency)) {
      throw new IllegalArgumentException(
          "PayrollPosted control total currency "
              + controlCurrency
              + " differs from the run's established currency "
              + currency.strip()
              + " (run "
              + payrollRunId
              + " seq "
              + runSeq
              + "); the control total is stored as minor units and reconstructed in the run"
              + " currency, so the currencies must match (no FX in scope)");
    }
    this.controlTotalMinor = controlTotal.amountMinor();
    this.usesIllustrativeRules = this.usesIllustrativeRules || runIllustrative;
  }

  /**
   * The single reconcile-decision both writer paths share (#35): compares the running {@code
   * allocatedSum} against the recorded {@code controlTotal} and transitions the run to {@link
   * PayrollRunState#RECONCILED} on a match or {@link PayrollRunState#RECONCILE_FAILED} on a
   * mismatch, returning whether it reconciled. {@link Money#equals} returns {@code false} (never
   * throws) on a currency mismatch, so the inequality branch safely backstops that too. Terminal
   * states ({@link PayrollRunState#SUPERSEDED} / {@link PayrollRunState#CURRENCY_MISMATCH}) are
   * honoured by {@link #transitionTo}, which refuses to clobber them — so a once-superseded or
   * once-divergent run is never reconciled back. Callers own logging (the two paths log at
   * different verbosity) and any pre-checks (e.g. a missing control total).
   *
   * @return {@code true} if the run reconciled (allocated == control), {@code false} otherwise
   */
  public boolean reconcileAgainstControl() {
    Money controlTotal = controlTotal();
    boolean reconciled = controlTotal != null && allocatedSum().equals(controlTotal);
    transitionTo(reconciled ? PayrollRunState.RECONCILED : PayrollRunState.RECONCILE_FAILED);
    return reconciled;
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

  public String getRunType() {
    return runType;
  }

  public PayrollRunState getState() {
    return state;
  }

  public UUID getLiabilityEntryId() {
    return liabilityEntryId;
  }

  public LiabilityState getLiabilityState() {
    return liabilityState;
  }

  /**
   * Records the run's freshly-posted LIABILITY recognition entry (ADR 0032, Track P phase P4) and
   * marks the liability dimension {@link LiabilityState#POSTED}. Called once per run by {@code
   * PayrollLiabilityWriter} on the first delivery of its {@code PayrollLiabilitiesPosted}.
   */
  public void markLiabilityPosted(UUID entryId) {
    this.liabilityEntryId = Objects.requireNonNull(entryId, "entryId");
    this.liabilityState = LiabilityState.POSTED;
  }

  /**
   * Marks the liability dimension {@link LiabilityState#POSTED} with NO recognised entry — the
   * all-zero-run case (ADR 0032, Track P phase P5 review W1): a run whose employer-cost total AND
   * every liability bucket are zero has nothing to recognise ({@code PayrollLiabilityWriter} never
   * builds a {@code JournalEntry} for it — a fewer-than-2-line entry would fail {@link
   * id.co.nativeapp.finance.gl.domain.JournalEntry#balanced}). The lifecycle is still COMPLETE:
   * {@link #liabilityEntryId} stays {@code null}, and a later corrective run's supersession scan
   * still finds this row (via {@code liability_state = 'POSTED'}, not gated on a non-null entry id)
   * and flips it to {@link LiabilityState#SUPERSEDED} — reversing it is a documented no-op ({@code
   * PayrollLiabilityWriter#reversePriorRunLiability} returns immediately for a {@code null} prior
   * entry id) since nothing was ever posted for it.
   */
  public void markLiabilityPostedEmpty() {
    this.liabilityEntryId = null;
    this.liabilityState = LiabilityState.POSTED;
  }

  /**
   * Marks the run's liability dimension {@link LiabilityState#SUPERSEDED} — either because a higher
   * {@code run_seq} of the same {@code run_type} reversed this run's liability entry, or because
   * THIS run itself arrived after a higher-seq run already posted (the out-of-order
   * self-supersession case, mirroring {@code LaborCostPostingWriter} step 2a/6). Idempotent:
   * re-applying to an already-{@code SUPERSEDED} row is a harmless no-op (unlike {@link
   * PayrollRunState}, the liability dimension has no self-heal path to guard against, so no
   * terminal-state check is needed here).
   */
  public void markLiabilitySuperseded() {
    this.liabilityState = LiabilityState.SUPERSEDED;
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
