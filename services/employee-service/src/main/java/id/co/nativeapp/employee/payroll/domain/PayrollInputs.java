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
   *     is unprorated (identity division) — byte-identical to the pre-W2 behaviour. VALIDATED to
   *     {@code [1, 12]} (Track P phase P3 review S3): {@code monthsInYear = 0} would silently zero
   *     the occupational-cost cap via {@code mulDiv(0, 12)} rather than fail loudly, and a value
   *     above 12 is not a meaningful count of months within one fiscal year.
   */
  public record AnnualContext(
      long cumulativeGrossBrutoMinor,
      long cumulativeDeductibleSocialMinor,
      long cumulativeWithheldMinor,
      int monthsInYear) {

    /**
     * Fails loudly on a {@code monthsInYear} outside {@code [1, 12]} rather than let the writer
     * hand the calculator a value that would silently zero (0) or misrepresent (&gt;12) the
     * biaya-jabatan cap proration (Track P phase P3 review S3).
     */
    public AnnualContext {
      if (monthsInYear < 1 || monthsInYear > 12) {
        throw new IllegalArgumentException(
            "AnnualContext.monthsInYear must be within [1, 12] (a fiscal-year month count"
                + " inclusive of this run's own month), got "
                + monthsInYear
                + " — 0 would silently zero the biaya-jabatan occupational-cost cap via"
                + " mulDiv(0, 12) instead of failing loudly");
      }
    }
  }

  /**
   * The Track P Phase P8 (ADR 0035) THR-month TER interplay context: this employee's ALREADY-
   * ACTIVE gross bruto / withheld PPh21 for the SAME period from the OTHER {@link RunType} (the
   * "sibling" run) — {@code null} when no such sibling run is currently active for this period. The
   * PMK 168/2023 masa-pajak mandate is that TER applies to the MONTH's TOTAL gross bruto, so
   * whichever of a THR/REGULAR pair for the same period computes SECOND must fold the sibling's
   * already-withheld figure into its own TER computation (see {@code GrossToNetCalculator}'s
   * TER_TABLE branch and {@code PayrollRunWriter#siblingPeriodContext}'s javadoc for the full
   * order-invariance proof). Mirrors {@link AnnualContext}'s writer-injects- history shape but
   * scoped to ONE sibling run in the SAME period rather than a whole fiscal year.
   *
   * @param grossBrutoMinor the sibling run's gross bruto (taxable cash earnings + taxable employer
   *     premiums) for this employee, minor units of the run's base currency
   * @param withheldMinor the sibling run's PPh21 already withheld for this employee, minor units
   */
  public record SiblingPeriodContext(long grossBrutoMinor, long withheldMinor) {}

  /**
   * Everything the calculator needs for one person, on the run's base currency.
   *
   * @param employeeId the person
   * @param ptkpStatus the PTKP relief status (drives the income-tax relief lookup)
   * @param baseComponent the BASE pay component (catalog config for the base earning line) — for a
   *     THR run this is instead the THR earning component (Track P Phase P8): {@code
   *     GrossToNetCalculator} always emits exactly one unconditional line for {@code
   *     (baseComponent, basePay)}, so a THR run's writer substitutes the THR component/prorated
   *     amount here rather than BASE, with no calculator change needed
   * @param basePay the person's aggregated base pay (decrypted), or the prorated THR earning for a
   *     THR run
   * @param earnings the non-base earning inputs (allowances, commission, etc.) — always empty for a
   *     THR run (statutory spec: THR carries ONLY the THR earning + PPh21)
   * @param statutoryComponents the statutory pay-components (BPJS-shaped + PPh21-shaped) in display
   *     order, ALREADY FILTERED by {@link PayComponent#appliesTo(RunType)} for this run's type
   *     (Track P Phase P8 — BPJS legs are filtered out of a THR run); each links to a {@link
   *     StatutoryRule} via its {@code statutory_rule_key}
   * @param otherDeductions non-statutory deductions
   * @param resolvedRules the FROZEN resolved statutory rules (rule_key -> rule)
   * @param hasNpwp whether the employee has an NPWP on file — drives the {@code TER_TABLE}/{@code
   *     ANNUAL_PROGRESSIVE} no-NPWP x120% surcharge (UU PPh Art 21(5a))
   * @param annualContext the December/final-month true-up context, or {@code null} for every other
   *     month AND for every THR run (Track P Phase P8 documented residual — the December true-up
   *     does not yet compose with a same-period THR sibling; see {@code PayrollRunWriter}'s class
   *     javadoc)
   * @param siblingContext the THR-month TER interplay context (Track P Phase P8), or {@code null}
   *     when no sibling run is currently active for this period
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
      AnnualContext annualContext,
      SiblingPeriodContext siblingContext) {

    /**
     * Backward-compatible constructor defaulting {@link #siblingContext} to {@code null} (Track P
     * Phase P8, additive) — every pre-P8 call site keeps compiling/behaving unchanged.
     */
    public PersonInput(
        UUID employeeId,
        PtkpStatus ptkpStatus,
        PayComponent baseComponent,
        Money basePay,
        List<EarningInput> earnings,
        List<PayComponent> statutoryComponents,
        List<DeductionInput> otherDeductions,
        Map<String, StatutoryRule> resolvedRules,
        boolean hasNpwp,
        AnnualContext annualContext) {
      this(
          employeeId,
          ptkpStatus,
          baseComponent,
          basePay,
          earnings,
          statutoryComponents,
          otherDeductions,
          resolvedRules,
          hasNpwp,
          annualContext,
          null);
    }
  }
}
