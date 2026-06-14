package id.co.nativeapp.employee.payroll;

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
 * A {@code labor_cost_allocation} row (design §1/§4): per (payroll_run, employee, outlet org_unit,
 * gl_account) the allocated employer-borne labor cost. This is a cost-center AGGREGATE, NOT an
 * individual gross-salary line, so it is NOT PII and uses the plain two-column {@link
 * MoneyEmbeddable} (queryable) rather than encryption — it feeds the {@code LaborCostAllocated}
 * event and finance's dimensional ledger.
 *
 * <p>Rows are produced by the largest-remainder allocation (exact-sum, no leakage) and then
 * aggregated per (outlet, gl_account) for the event. Extends {@link Auditable} (rule 4); under the
 * {@code labor_cost_allocation} RLS policy (rule 5).
 */
@Entity
@Table(name = "labor_cost_allocation")
public class LaborCostAllocation extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payroll_run_id", nullable = false)
  private UUID payrollRunId;

  @Column(name = "employee_id", nullable = false)
  private UUID employeeId;

  @Column(name = "outlet_org_unit_id", nullable = false)
  private UUID outletOrgUnitId;

  @Column(name = "legal_employer_id", nullable = false)
  private UUID legalEmployerId;

  @Column(name = "gl_account", nullable = false, length = 64)
  private String glAccount;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "earnings_share_bp", nullable = false)
  private int earningsShareBp;

  protected LaborCostAllocation() {
    // for JPA
  }

  /** Creates a labor-cost allocation row with a freshly generated id. */
  public LaborCostAllocation(
      UUID payrollRunId,
      UUID employeeId,
      UUID outletOrgUnitId,
      UUID legalEmployerId,
      String glAccount,
      Money amount,
      int earningsShareBp) {
    this.id = UUID.randomUUID();
    this.payrollRunId = Objects.requireNonNull(payrollRunId, "payrollRunId");
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.outletOrgUnitId = Objects.requireNonNull(outletOrgUnitId, "outletOrgUnitId");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.glAccount = Objects.requireNonNull(glAccount, "glAccount");
    this.amount = MoneyEmbeddable.of(Objects.requireNonNull(amount, "amount"));
    this.earningsShareBp = earningsShareBp;
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

  public UUID getOutletOrgUnitId() {
    return outletOrgUnitId;
  }

  public UUID getLegalEmployerId() {
    return legalEmployerId;
  }

  public String getGlAccount() {
    return glAccount;
  }

  public Money getAmount() {
    return amount.toMoney();
  }

  public int getEarningsShareBp() {
    return earningsShareBp;
  }

  @Override
  public String toString() {
    return "LaborCostAllocation[id="
        + id
        + ", outletOrgUnitId="
        + outletOrgUnitId
        + ", glAccount="
        + glAccount
        + "]";
  }
}
