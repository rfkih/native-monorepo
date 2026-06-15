package id.co.nativeapp.finance.consolidation;

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
 * The {@code consolidation_summary} aggregate (P3d SEAM 3a) — the per-{@code (group, period,
 * close_run_seq)} roll-up of a group close: {@code group_revenue} / {@code group_expense} / {@code
 * group_net} (all {@link Money} in the group's reporting currency) plus the close {@link
 * ConsolidationState}.
 *
 * <p><strong>Three amounts, one shared reporting currency.</strong> Revenue, expense and net are
 * all in the group's reporting currency, so the entity stores three {@code BIGINT} minor columns
 * and a single {@code reporting_currency CHAR(3)} (rather than three {@link
 * id.co.nativeapp.finance.revenue.MoneyEmbeddable}s, which would carry three redundant currency
 * columns). {@link #groupRevenue()} / {@link #groupExpense()} / {@link #groupNet()} reconstruct the
 * {@link Money} value type (rule 8 — integer minor units + ISO-4217, never a float).
 *
 * <p><strong>Append-only supersession (#23).</strong> A higher {@code close_run_seq} for the same
 * {@code (group, period)} is a NEW summary row; the prior summary is flipped to the TERMINAL {@link
 * ConsolidationState#SUPERSEDED} (sticky — mirrors {@code PayrollRunState.SUPERSEDED}), never
 * overwritten. So the latest non-SUPERSEDED summary for a period IS the close.
 *
 * <p>Under the two-GUC conjunction RLS ({@code group_id = current_group AND company_id =
 * current_tenant}); {@code company_id} is the group's LEAD (rule 4).
 */
@Entity
@Table(name = "consolidation_summary")
public class ConsolidationSummary extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "reporting_currency", nullable = false, updatable = false, length = 3)
  private String reportingCurrency;

  @Column(name = "group_revenue_minor", nullable = false)
  private long groupRevenueMinor;

  @Column(name = "group_expense_minor", nullable = false)
  private long groupExpenseMinor;

  @Column(name = "group_net_minor", nullable = false)
  private long groupNetMinor;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private ConsolidationState state;

  @Column(name = "uses_stub_fx", nullable = false)
  private boolean usesStubFx;

  @Column(name = "uses_illustrative_rules", nullable = false)
  private boolean usesIllustrativeRules;

  /**
   * (P3d SEAM 3b) Whether this close translated any member figures under the SIMPLIFIED, FLAGGED
   * multi-currency policy ({@link SimplifiedTranslationPolicy}). Sticky/monotonic: true for any
   * multi-currency / translated close, so a future dashboard badges the consolidation PROVISIONAL
   * ("needs accounting sign-off"). A same-currency (3a) close leaves it false — no translation
   * happened.
   */
  @Column(name = "uses_simplified_translation_policy", nullable = false)
  private boolean usesSimplifiedTranslationPolicy;

  @Column(name = "close_run_seq", nullable = false, updatable = false)
  private int closeRunSeq;

  protected ConsolidationSummary() {
    // for JPA
  }

  /**
   * Creates a SAME-CURRENCY (3a) / held consolidation summary: no FX, no simplified translation.
   * The figures are already in the reporting currency. {@code group_net} is derived as {@code
   * revenue - expense} and must share the reporting currency.
   *
   * @param groupId the consolidation group
   * @param period the accounting period {@code YYYY-MM}
   * @param reportingCurrency the group's reporting ISO-4217 currency (every amount is in it)
   * @param groupRevenue the post-elimination consolidated revenue (in the reporting currency)
   * @param groupExpense the post-elimination consolidated expense (in the reporting currency)
   * @param state the close lifecycle state
   * @param usesIllustrativeRules sticky-OR from the member trial balances
   * @param closeRunSeq the close run that produced this summary
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public ConsolidationSummary(
      UUID groupId,
      String period,
      String reportingCurrency,
      Money groupRevenue,
      Money groupExpense,
      ConsolidationState state,
      boolean usesIllustrativeRules,
      int closeRunSeq) {
    this(
        groupId,
        period,
        reportingCurrency,
        groupRevenue,
        groupExpense,
        state,
        usesIllustrativeRules,
        false,
        false,
        closeRunSeq);
  }

  /**
   * Creates a consolidation summary for one close run, carrying the SEAM-3b multi-currency flags.
   * {@code group_net} is derived as {@code revenue - expense} and must share the reporting
   * currency.
   *
   * @param groupId the consolidation group
   * @param period the accounting period {@code YYYY-MM}
   * @param reportingCurrency the group's reporting ISO-4217 currency (every amount is in it)
   * @param groupRevenue the post-elimination consolidated revenue (in the reporting currency)
   * @param groupExpense the post-elimination consolidated expense (in the reporting currency)
   * @param state the close lifecycle state
   * @param usesIllustrativeRules sticky-OR from the member trial balances
   * @param usesStubFx sticky-OR across every translated line (true if any applied rate was a stub)
   * @param usesSimplifiedTranslationPolicy true for any multi-currency / translated close (sticky)
   * @param closeRunSeq the close run that produced this summary
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public ConsolidationSummary(
      UUID groupId,
      String period,
      String reportingCurrency,
      Money groupRevenue,
      Money groupExpense,
      ConsolidationState state,
      boolean usesIllustrativeRules,
      boolean usesStubFx,
      boolean usesSimplifiedTranslationPolicy,
      int closeRunSeq) {
    Objects.requireNonNull(reportingCurrency, "reportingCurrency");
    Objects.requireNonNull(groupRevenue, "groupRevenue");
    Objects.requireNonNull(groupExpense, "groupExpense");
    // All three amounts must share the reporting currency. Money.minus throws on a mismatch — the
    // last-line defence that net cannot silently mix currencies (post-translation every figure is
    // in the reporting currency by construction).
    Money net = groupRevenue.minus(groupExpense);
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.period = Objects.requireNonNull(period, "period");
    this.reportingCurrency = reportingCurrency.strip();
    this.groupRevenueMinor = groupRevenue.amountMinor();
    this.groupExpenseMinor = groupExpense.amountMinor();
    this.groupNetMinor = net.amountMinor();
    this.state = Objects.requireNonNull(state, "state");
    this.usesStubFx = usesStubFx;
    this.usesIllustrativeRules = usesIllustrativeRules;
    this.usesSimplifiedTranslationPolicy = usesSimplifiedTranslationPolicy;
    this.closeRunSeq = closeRunSeq;
  }

  /**
   * Flips this summary to a new {@link ConsolidationState}. {@link ConsolidationState#SUPERSEDED}
   * is TERMINAL / sticky (mirrors {@code PayrollRunLedger.transitionTo}): once superseded, a later
   * transition away is a no-op so a reversed close can never display as CLOSED again.
   */
  public void transitionTo(ConsolidationState newState) {
    Objects.requireNonNull(newState, "newState");
    if (this.state == ConsolidationState.SUPERSEDED && newState != ConsolidationState.SUPERSEDED) {
      return;
    }
    this.state = newState;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public String getPeriod() {
    return period;
  }

  /** The group's reporting currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getReportingCurrency() {
    return reportingCurrency.strip();
  }

  /** The consolidated revenue as {@link Money} (in the reporting currency). */
  public Money groupRevenue() {
    return Money.ofMinor(groupRevenueMinor, getReportingCurrency());
  }

  /** The consolidated expense as {@link Money} (in the reporting currency). */
  public Money groupExpense() {
    return Money.ofMinor(groupExpenseMinor, getReportingCurrency());
  }

  /**
   * The consolidated net ({@code revenue - expense}) as {@link Money} (in the reporting currency).
   */
  public Money groupNet() {
    return Money.ofMinor(groupNetMinor, getReportingCurrency());
  }

  public ConsolidationState getState() {
    return state;
  }

  public boolean isUsesStubFx() {
    return usesStubFx;
  }

  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }

  /**
   * Whether this close translated any member figures under the SIMPLIFIED, FLAGGED multi-currency
   * policy (SEAM 3b). True means the consolidation is PROVISIONAL — see {@link
   * SimplifiedTranslationPolicy}.
   */
  public boolean isUsesSimplifiedTranslationPolicy() {
    return usesSimplifiedTranslationPolicy;
  }

  public int getCloseRunSeq() {
    return closeRunSeq;
  }
}
