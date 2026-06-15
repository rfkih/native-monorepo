package id.co.nativeapp.employee.payroll.dto;

import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * The immutable output of the pure {@link GrossToNetCalculator} for one person: the computed
 * payslip lines plus the four roll-up totals. The {@link PayrollRunWriter} persists the lines
 * (encrypting amounts) and rolls the per-person totals up into the company-level {@link PayrollRun}
 * totals.
 */
public final class PayrollResult {

  private PayrollResult() {
    // value-type holder
  }

  /**
   * One computed line, ready to persist as a {@link PayslipLine}. {@code amount}/{@code calcBasis}
   * are individual PII (encrypted on persist, never logged/evented).
   */
  public record ComputedLine(
      PayComponent component,
      Money amount,
      Money calcBasis,
      String ruleVersion,
      boolean illustrative) {}

  /**
   * The per-person result.
   *
   * @param employeeId the person
   * @param lines the computed payslip lines (in pipeline order)
   * @param grossEarnings sum of EARNING lines
   * @param employeeDeductions sum of EMPLOYEE-bearing DEDUCTION lines (reduce net)
   * @param employerContributions sum of EMPLOYER-bearing lines (labor cost, NOT net)
   * @param net grossEarnings - employeeDeductions
   * @param usesIllustrativeRules whether any statutory line used an illustrative rule
   */
  public record PersonResult(
      UUID employeeId,
      List<ComputedLine> lines,
      Money grossEarnings,
      Money employeeDeductions,
      Money employerContributions,
      Money net,
      boolean usesIllustrativeRules) {

    /**
     * The total employer-borne labor cost for this person = gross earnings + employer contributions
     * (the amount the allocator distributes across outlets).
     */
    public Money employerLaborCost() {
      return grossEarnings.plus(employerContributions);
    }
  }
}
