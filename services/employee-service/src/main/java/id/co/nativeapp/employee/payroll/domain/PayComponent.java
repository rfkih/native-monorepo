package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The catalog of an earning or deduction component (design §1) — pure CONFIG, it carries no amounts
 * and therefore NO PII. A component declares its {@code component_key} (e.g. {@code BASE}, {@code
 * MEAL_ALLOWANCE}, {@code BPJS_KES}, {@code PPH21}), its {@link PayComponentKind kind}, its {@link
 * CalcType calc_type}, its {@link PayComponentBearer bearer}, the {@code gl_account} it posts to,
 * whether it is {@code taxable}, and — for a statutory component — the {@code statutory_rule_key}
 * linking it to its rule family.
 *
 * <p>It extends {@link Auditable} (rule 4) and is under the {@code pay_component} RLS policy (rule
 * 5).
 */
@Entity
@Table(name = "pay_component")
public class PayComponent extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "component_key", nullable = false, length = 64)
  private String componentKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private PayComponentKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "calc_type", nullable = false, length = 32)
  private CalcType calcType;

  @Enumerated(EnumType.STRING)
  @Column(name = "bearer", nullable = false, length = 16)
  private PayComponentBearer bearer;

  @Column(name = "gl_account", nullable = false, length = 64)
  private String glAccount;

  @Column(name = "taxable", nullable = false)
  private boolean taxable;

  /**
   * Links a statutory component to its {@link StatutoryRule} family ({@code rule_key}); null for a
   * non-statutory component.
   */
  @Column(name = "statutory_rule_key", length = 64)
  private String statutoryRuleKey;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  @Column(name = "active", nullable = false)
  private boolean active;

  /**
   * Track P Phase P8 (ADR 0035): which {@link RunType}(s) this component participates in — {@code
   * ALL} (default, every pre-P8 component), {@code REGULAR}, or {@code THR}. See {@link RunScope}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "run_scope", nullable = false, length = 16)
  private RunScope runScope;

  protected PayComponent() {
    // for JPA
  }

  /**
   * Creates a pay-component catalog entry with a freshly generated id, defaulting {@link #runScope}
   * to {@link RunScope#ALL} (the pre-P8 shape — every existing call site keeps compiling/behaving
   * unchanged).
   */
  public PayComponent(
      String componentKey,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      String glAccount,
      boolean taxable,
      String statutoryRuleKey,
      int displayOrder) {
    this(
        componentKey,
        kind,
        calcType,
        bearer,
        glAccount,
        taxable,
        statutoryRuleKey,
        displayOrder,
        RunScope.ALL);
  }

  /** Creates a pay-component catalog entry with an explicit {@link RunScope} (Track P Phase P8). */
  public PayComponent(
      String componentKey,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      String glAccount,
      boolean taxable,
      String statutoryRuleKey,
      int displayOrder,
      RunScope runScope) {
    this.id = UUID.randomUUID();
    this.componentKey = Objects.requireNonNull(componentKey, "componentKey");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.calcType = Objects.requireNonNull(calcType, "calcType");
    this.bearer = Objects.requireNonNull(bearer, "bearer");
    this.glAccount = Objects.requireNonNull(glAccount, "glAccount");
    this.taxable = taxable;
    this.statutoryRuleKey = statutoryRuleKey;
    this.displayOrder = displayOrder;
    this.active = true;
    this.runScope = Objects.requireNonNull(runScope, "runScope");
  }

  /** Copy constructor for {@link #asAnnualTrueUpVariant} — preserves the SAME {@code id}. */
  private PayComponent(
      UUID id,
      String componentKey,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      String glAccount,
      boolean taxable,
      String statutoryRuleKey,
      int displayOrder,
      boolean active,
      RunScope runScope) {
    this.id = id;
    this.componentKey = componentKey;
    this.kind = kind;
    this.calcType = calcType;
    this.bearer = bearer;
    this.glAccount = glAccount;
    this.taxable = taxable;
    this.statutoryRuleKey = statutoryRuleKey;
    this.displayOrder = displayOrder;
    this.active = active;
    this.runScope = runScope;
  }

  /**
   * Returns an in-memory VARIANT of this component that resolves against {@code annualRuleKey}
   * instead of its catalog {@link #statutoryRuleKey} — used ONLY by {@code PayrollRunWriter} for
   * the December/final-month Art-17 true-up (Track P phase P3) to swap the monthly income-tax
   * component (e.g. {@code PPH21}, normally wired to {@code PPH21_TER}) onto the resolved {@code
   * ANNUAL_PROGRESSIVE} rule ({@code PPH21_ARTICLE17}) for that ONE month's compute — so {@link
   * id.co.nativeapp.employee.payroll.service.GrossToNetCalculator}'s {@code
   * statutoryByCalc(ANNUAL_PROGRESSIVE)} finds it and, just as importantly, {@code
   * statutoryByCalc(TER_TABLE)}/{@code PROGRESSIVE_BRACKET} no longer does — December must use
   * EXACTLY ONE income-tax family, never both (that would double-tax the month).
   *
   * <p>NEVER persisted: no repository ever sees this instance, and the catalog row itself is left
   * completely untouched — every OTHER month still resolves the real {@link #statutoryRuleKey}
   * normally. The {@code id} is preserved (not a fresh random one) so the resulting {@code
   * payslip_line.pay_component_id} still traces back to the real catalog row; every other catalog
   * field ({@code component_key}, {@code kind}, {@code bearer}, {@code gl_account}, {@code
   * taxable}, {@code display_order}) is copied unchanged.
   */
  public PayComponent asAnnualTrueUpVariant(String annualRuleKey) {
    Objects.requireNonNull(annualRuleKey, "annualRuleKey");
    return new PayComponent(
        this.id,
        this.componentKey,
        this.kind,
        this.calcType,
        this.bearer,
        this.glAccount,
        this.taxable,
        annualRuleKey,
        this.displayOrder,
        this.active,
        this.runScope);
  }

  /**
   * Aligns this component's catalog fields with a dataset's declared row (Track P phase P2 official
   * seed/upsert) — used both to REWIRE an existing component's {@link #statutoryRuleKey} (e.g.
   * {@code PPH21_PROGRESSIVE -> PPH21_TER}) and to keep every other field in sync with a dataset
   * revision. Never touches {@link #componentKey} (the natural key) or {@link #active}.
   *
   * @return {@code true} if any field actually changed (so the caller can skip a no-op save — the
   *     idempotent-reseed proof)
   */
  public boolean applyCatalog(
      PayComponentKind newKind,
      CalcType newCalcType,
      PayComponentBearer newBearer,
      String newGlAccount,
      boolean newTaxable,
      String newStatutoryRuleKey,
      int newDisplayOrder,
      RunScope newRunScope) {
    boolean changed = false;
    if (this.kind != newKind) {
      this.kind = newKind;
      changed = true;
    }
    if (this.calcType != newCalcType) {
      this.calcType = newCalcType;
      changed = true;
    }
    if (this.bearer != newBearer) {
      this.bearer = newBearer;
      changed = true;
    }
    if (!this.glAccount.equals(newGlAccount)) {
      this.glAccount = newGlAccount;
      changed = true;
    }
    if (this.taxable != newTaxable) {
      this.taxable = newTaxable;
      changed = true;
    }
    if (!Objects.equals(this.statutoryRuleKey, newStatutoryRuleKey)) {
      this.statutoryRuleKey = newStatutoryRuleKey;
      changed = true;
    }
    if (this.displayOrder != newDisplayOrder) {
      this.displayOrder = newDisplayOrder;
      changed = true;
    }
    if (this.runScope != newRunScope) {
      this.runScope = newRunScope;
      changed = true;
    }
    return changed;
  }

  public UUID getId() {
    return id;
  }

  public String getComponentKey() {
    return componentKey;
  }

  public PayComponentKind getKind() {
    return kind;
  }

  public CalcType getCalcType() {
    return calcType;
  }

  public PayComponentBearer getBearer() {
    return bearer;
  }

  public String getGlAccount() {
    return glAccount;
  }

  public boolean isTaxable() {
    return taxable;
  }

  public String getStatutoryRuleKey() {
    return statutoryRuleKey;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public boolean isActive() {
    return active;
  }

  public RunScope getRunScope() {
    return runScope;
  }

  /** Whether this component resolves on a run of {@code runType} (Track P Phase P8). */
  public boolean appliesTo(RunType runType) {
    return runScope.appliesTo(runType);
  }

  @Override
  public String toString() {
    return "PayComponent[id=" + id + ", key=" + componentKey + ", kind=" + kind + "]";
  }
}
