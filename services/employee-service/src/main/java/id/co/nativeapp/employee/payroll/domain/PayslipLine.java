package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code payslip_line} (design §1): one line per (payroll_run, employee, pay_component). Its
 * {@code amount} and {@code calc_basis} are <strong>individual salary — PII (rule 6)</strong> and
 * are therefore <strong>column-encrypted at rest</strong> via {@link MoneyPiiConverter} into {@code
 * amount_enc}/{@code calc_basis_enc} ciphertext columns; they NEVER appear in any event or log.
 *
 * <p>Each line STAMPS the {@code rule_version} that produced it (HR-7 reproducibility) and carries
 * {@code is_illustrative} (the per-line illustrative flag). Extends {@link Auditable} (rule 4);
 * under the {@code payslip_line} RLS policy (rule 5).
 */
@Entity
@Table(name = "payslip_line")
public class PayslipLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payroll_run_id", nullable = false)
  private UUID payrollRunId;

  @Column(name = "employee_id", nullable = false)
  private UUID employeeId;

  @Column(name = "pay_component_id", nullable = false)
  private UUID payComponentId;

  @Column(name = "component_key", nullable = false, length = 64)
  private String componentKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private PayComponentKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "bearer", nullable = false, length = 16)
  private PayComponentBearer bearer;

  @Column(name = "gl_account", nullable = false, length = 64)
  private String glAccount;

  /** The line amount — individual salary, PII, encrypted at rest. */
  @Convert(converter = MoneyPiiConverter.class)
  @Column(name = "amount_enc", nullable = false, columnDefinition = "text")
  private Money amount;

  /**
   * The basis the amount was computed from (e.g. the capped base / taxable base) — PII, encrypted.
   */
  @Convert(converter = MoneyPiiConverter.class)
  @Column(name = "calc_basis_enc", columnDefinition = "text")
  private Money calcBasis;

  /** The statutory rule version that produced this line (null for a non-statutory line). HR-7. */
  @Column(name = "rule_version", length = 64)
  private String ruleVersion;

  @Column(name = "is_illustrative", nullable = false)
  private boolean illustrative;

  protected PayslipLine() {
    // for JPA
  }

  /** Creates a payslip line with a freshly generated id. */
  public PayslipLine(
      UUID payrollRunId,
      UUID employeeId,
      UUID payComponentId,
      String componentKey,
      PayComponentKind kind,
      PayComponentBearer bearer,
      String glAccount,
      Money amount,
      Money calcBasis,
      String ruleVersion,
      boolean illustrative) {
    this.id = UUID.randomUUID();
    this.payrollRunId = Objects.requireNonNull(payrollRunId, "payrollRunId");
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.payComponentId = Objects.requireNonNull(payComponentId, "payComponentId");
    this.componentKey = Objects.requireNonNull(componentKey, "componentKey");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.bearer = Objects.requireNonNull(bearer, "bearer");
    this.glAccount = Objects.requireNonNull(glAccount, "glAccount");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.calcBasis = calcBasis;
    this.ruleVersion = ruleVersion;
    this.illustrative = illustrative;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPayrollRunId() {
    return payrollRunId;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getPayComponentId() {
    return payComponentId;
  }

  public String getComponentKey() {
    return componentKey;
  }

  public PayComponentKind getKind() {
    return kind;
  }

  public PayComponentBearer getBearer() {
    return bearer;
  }

  public String getGlAccount() {
    return glAccount;
  }

  /** The decrypted line amount (PII). Restricted to the service; never logged/evented. */
  public Money getAmount() {
    return amount;
  }

  public Money getCalcBasis() {
    return calcBasis;
  }

  public String getRuleVersion() {
    return ruleVersion;
  }

  public boolean isIllustrative() {
    return illustrative;
  }

  /** PII-safe: id + component key + bearer only, NEVER the amount (rule 6). */
  @Override
  public String toString() {
    return "PayslipLine[id=" + id + ", componentKey=" + componentKey + ", bearer=" + bearer + "]";
  }
}
