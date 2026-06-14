package id.co.nativeapp.employee.payroll;

/**
 * The calculation shape of a {@link StatutoryRule}. This is the ALGORITHM family (real, public, in
 * Java); the actual thresholds, rates, ceilings and relief amounts are 100% DATA in the rule's
 * {@code params_json} (HR-9 — zero statutory figures in Java):
 *
 * <ul>
 *   <li>{@code PROGRESSIVE_BRACKET} — a PPh21-shaped income tax: walk sorted {@code [floor, cap,
 *       rate_bp]} brackets accumulating tax. Params: {@code brackets[]}.
 *   <li>{@code PERCENTAGE_CEILING} — a BPJS-shaped contribution: a percentage of a base capped at a
 *       ceiling, split employer/employee. Params: {@code ceiling_minor, employee_bp, employer_bp,
 *       reduces_tax_base}.
 *   <li>{@code RELIEF_TABLE} — a PTKP-shaped relief lookup: a per-status non-taxable allowance
 *       subtracted before the progressive brackets. Params: {@code ptkp { TK0: minor, ... }}.
 * </ul>
 */
public enum StatutoryCalcType {
  PROGRESSIVE_BRACKET,
  PERCENTAGE_CEILING,
  RELIEF_TABLE
}
