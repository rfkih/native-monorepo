package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The immutable input model the pure {@link GrossToNetCalculator} consumes for one person. The
 * {@link PayrollRunWriter} resolves these from the DB (decrypting base pay / fixed amounts,
 * applying percent-of-base, reading the metric projection) BEFORE handing them to the calculator,
 * so the calculator stays a pure, Spring-free, deterministic function (HR-7 reproducibility). All
 * money is {@link Money} (minor units), in the run's single base currency.
 */
public final class PayrollInputs {

  private PayrollInputs() {
    // value-type holder
  }

  /**
   * One resolved earning input: the catalog component plus its already-computed Money amount.
   * {@code taxable} mirrors {@link PayComponent#isTaxable()} (the taxable-base contribution flag).
   */
  public record EarningInput(PayComponent component, Money amount) {
    public boolean taxable() {
      return component.isTaxable();
    }
  }

  /**
   * One resolved non-statutory deduction (loans etc.) — a fixed/percentage employee-borne
   * deduction.
   */
  public record DeductionInput(PayComponent component, Money amount) {}

  /**
   * Everything the calculator needs for one person, on the run's base currency.
   *
   * @param employeeId the person
   * @param ptkpStatus the PTKP relief status (drives the income-tax relief lookup)
   * @param baseComponent the BASE pay component (catalog config for the base earning line)
   * @param basePay the person's aggregated base pay (decrypted)
   * @param earnings the non-base earning inputs (allowances, commission, etc.)
   * @param statutoryComponents the statutory pay-components (BPJS-shaped + PPh21-shaped) in display
   *     order; each links to a {@link StatutoryRule} via its {@code statutory_rule_key}
   * @param otherDeductions non-statutory deductions
   * @param resolvedRules the FROZEN resolved statutory rules (rule_key -> rule)
   */
  public record PersonInput(
      UUID employeeId,
      PtkpStatus ptkpStatus,
      PayComponent baseComponent,
      Money basePay,
      List<EarningInput> earnings,
      List<PayComponent> statutoryComponents,
      List<DeductionInput> otherDeductions,
      Map<String, StatutoryRule> resolvedRules) {}
}
