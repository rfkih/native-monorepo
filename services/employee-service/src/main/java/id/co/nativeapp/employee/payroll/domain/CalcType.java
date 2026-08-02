package id.co.nativeapp.employee.payroll.domain;

/**
 * How a {@link PayComponent} is calculated. A non-statutory component is {@code FIXED} (a fixed
 * Money amount from an {@link EarningRule}) or {@code PERCENTAGE} (a basis-point rate of the base);
 * a statutory component delegates its NUMBERS to a {@link StatutoryRule} ({@code
 * STATUTORY_PROGRESSIVE} for a PPh21-shaped legacy annualize/de-annualize progressive-bracket tax,
 * {@code STATUTORY_TER} for a PPh21-shaped PMK 168/2023 monthly effective-rate (TER) tax, {@code
 * STATUTORY_CEILING} for a BPJS-shaped percentage-with-ceiling contribution). This label is purely
 * DESCRIPTIVE (a catalog/display concern) — {@link
 * id.co.nativeapp.employee.payroll.service.GrossToNetCalculator} never dispatches on it; it
 * dispatches on the LINKED {@link StatutoryRule}'s {@link StatutoryCalcType}, resolved via {@link
 * PayComponent#getStatutoryRuleKey()}. Crucially the machinery lives in Java but every statutory
 * FIGURE is data in the rule (HR-9) — see {@link StatutoryRule}.
 */
public enum CalcType {
  FIXED,
  PERCENTAGE,
  STATUTORY_PROGRESSIVE,
  STATUTORY_TER,
  STATUTORY_CEILING
}
