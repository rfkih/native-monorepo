package id.co.nativeapp.employee.payroll.domain;

/**
 * How a {@link PayComponent} is calculated. A non-statutory component is {@code FIXED} (a fixed
 * Money amount from an {@link EarningRule}) or {@code PERCENTAGE} (a basis-point rate of the base);
 * a statutory component delegates its NUMBERS to a {@link StatutoryRule} ({@code
 * STATUTORY_PROGRESSIVE} for a PPh21-shaped progressive-bracket tax, {@code STATUTORY_CEILING} for
 * a BPJS-shaped percentage-with-ceiling contribution). Crucially the machinery lives in Java but
 * every statutory FIGURE is data in the rule (HR-9) — see {@link StatutoryRule}.
 */
public enum CalcType {
  FIXED,
  PERCENTAGE,
  STATUTORY_PROGRESSIVE,
  STATUTORY_CEILING
}
