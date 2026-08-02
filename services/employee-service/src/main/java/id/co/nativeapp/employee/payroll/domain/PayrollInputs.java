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
   * The December / final-month Art-17 true-up context (Track P phases P1/P3): the employee's
   * year-to-date figures BEFORE this month, decrypted and summed by the writer from the active
   * payslip-line history (phase P3). {@code null} for every non-December run — the calculator only
   * computes the {@code ANNUAL_PROGRESSIVE} branch when BOTH a resolved {@code ANNUAL_PROGRESSIVE}
   * rule AND a non-null context are present, and throws if one is present without the other (a
   * misconfiguration must fail loudly, never silently skip the true-up or silently annualize
   * garbage).
   *
   * @param cumulativeGrossBrutoMinor Jan..(month-1) gross bruto (taxable cash earnings + taxable
   *     employer premiums), minor units of the run's base currency
   * @param cumulativeDeductibleSocialMinor Jan..(month-1) employee-deductible social legs (e.g.
   *     JHT-EE + JP-EE; BPJS-Kesehatan-EE is NOT deductible), minor units
   * @param cumulativeWithheldMinor Jan..(month-1) PPh21 actually withheld, minor units
   * @param monthsInYear how many months (inclusive of this run's own month) this employee was paid
   *     in the fiscal year so far — prorates the biaya-jabatan occupational-cost cap ({@code
   *     occupational_cost_cap_annual_minor × monthsInYear / 12}; PMK 250/2008's Rp500,000/month
   *     figure is inherently monthly, so a mid-year joiner's ANNUAL cap must scale down with them,
   *     Track P phase P3 W2). <strong>PTKP relief is deliberately NOT prorated</strong> — a
   *     full-year-resident employee's Art-17 employer annual reconciliation applies the FULL annual
   *     PTKP regardless of when in the year they joined (the domain spec is explicit on this; K/I
   *     spouse-combined PTKP proration is a separate, out-of-scope annual-return concern). A
   *     permanent employee paid the full year passes {@code monthsInYear = 12}, under which the cap
   *     is unprorated (identity division) — byte-identical to the pre-W2 behaviour.
   */
  public record AnnualContext(
      long cumulativeGrossBrutoMinor,
      long cumulativeDeductibleSocialMinor,
      long cumulativeWithheldMinor,
      int monthsInYear) {}

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
   * @param hasNpwp whether the employee has an NPWP on file — drives the {@code TER_TABLE}/{@code
   *     ANNUAL_PROGRESSIVE} no-NPWP x120% surcharge (UU PPh Art 21(5a))
   * @param annualContext the December/final-month true-up context, or {@code null} for every other
   *     month (see {@link AnnualContext})
   */
  public record PersonInput(
      UUID employeeId,
      PtkpStatus ptkpStatus,
      PayComponent baseComponent,
      Money basePay,
      List<EarningInput> earnings,
      List<PayComponent> statutoryComponents,
      List<DeductionInput> otherDeductions,
      Map<String, StatutoryRule> resolvedRules,
      boolean hasNpwp,
      AnnualContext annualContext) {}
}
