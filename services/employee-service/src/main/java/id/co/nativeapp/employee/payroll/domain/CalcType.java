package id.co.nativeapp.employee.payroll.domain;

/**
 * How a {@link PayComponent} is calculated. A non-statutory component is {@code FIXED} (a fixed
 * Money amount from an {@link EarningRule}) or {@code PERCENTAGE} (a basis-point rate of the base);
 * a statutory component delegates its NUMBERS to a {@link StatutoryRule} ({@code
 * STATUTORY_PROGRESSIVE} for a PPh21-shaped legacy annualize/de-annualize progressive-bracket tax,
 * {@code STATUTORY_TER} for a PPh21-shaped PMK 168/2023 monthly effective-rate (TER) tax, {@code
 * STATUTORY_CEILING} for a BPJS-shaped percentage-with-ceiling contribution, {@code
 * STATUTORY_HOURLY} for an {@code HOURLY_RATE_TABLE}-linked overtime earning). {@code
 * WORK_INPUT_DERIVED} (Track P phase P7) marks a component synthesized directly by {@code
 * PayrollRunWriter} from an APPROVED work-input source that is NOT itself a statutory rule table
 * (unpaid-leave proration reads the tenant's {@code work_calendar} divisor; the expense-claim
 * reimbursement line is a pass-through of {@code ExpenseClaimPayrollLinker
 * #findLinkedClaimTotalsByEmployee}) — {@code statutory_rule_key} is {@code null} for these. This
 * label is purely DESCRIPTIVE (a catalog/display concern) — {@link
 * id.co.nativeapp.employee.payroll.service.GrossToNetCalculator} never dispatches on it; a
 * statutory-linked component dispatches on the LINKED {@link StatutoryRule}'s {@link
 * StatutoryCalcType}, resolved via {@link PayComponent#getStatutoryRuleKey()} — {@code
 * STATUTORY_HOURLY}/{@code WORK_INPUT_DERIVED} components are instead resolved directly by {@code
 * PayrollRunWriter} into a plain {@code EarningInput} BEFORE the calculator ever sees them (never
 * through the calculator's own per-calc-type statutory dispatch loop). Crucially the machinery
 * lives in Java but every statutory FIGURE is data in the rule (HR-9) — see {@link StatutoryRule}.
 */
public enum CalcType {
  FIXED,
  PERCENTAGE,
  STATUTORY_PROGRESSIVE,
  STATUTORY_TER,
  STATUTORY_CEILING,
  STATUTORY_HOURLY,
  WORK_INPUT_DERIVED
}
