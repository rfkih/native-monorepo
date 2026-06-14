package id.co.nativeapp.employee.payroll;

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

  protected PayComponent() {
    // for JPA
  }

  /** Creates a pay-component catalog entry with a freshly generated id. */
  public PayComponent(
      String componentKey,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      String glAccount,
      boolean taxable,
      String statutoryRuleKey,
      int displayOrder) {
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

  @Override
  public String toString() {
    return "PayComponent[id=" + id + ", key=" + componentKey + ", kind=" + kind + "]";
  }
}
