package id.co.nativeapp.employee.payroll.domain;

/**
 * The calculation shape of a {@link StatutoryRule}. This is the ALGORITHM family (real, public, in
 * Java); the actual thresholds, rates, ceilings and relief amounts are 100% DATA in the rule's
 * {@code params_json} (HR-9 — zero statutory figures in Java):
 *
 * <ul>
 *   <li>{@code PROGRESSIVE_BRACKET} — a PPh21-shaped income tax: walk sorted {@code [floor, cap,
 *       rate_bp]} brackets accumulating tax. Params: {@code brackets[]}. The pre-TER
 *       annualize-then-de-annualize illustrative path; kept byte-identical (Track P phase P1).
 *   <li>{@code PERCENTAGE_CEILING} — a BPJS-shaped contribution: a percentage of a base capped at a
 *       ceiling, split employer/employee. Params: {@code ceiling_minor, employee_bp, employer_bp,
 *       reduces_tax_base, base_kind, employer_adds_to_tax_base}.
 *   <li>{@code RELIEF_TABLE} — a PTKP-shaped relief lookup: a per-status non-taxable allowance
 *       subtracted before the progressive brackets. Params: {@code ptkp { TK0: minor, ... }}.
 *   <li>{@code TER_TABLE} — PMK 168/2023's monthly PPh 21: an EFFECTIVE-rate lookup (the first
 *       band, ascending, whose {@code up_to_minor} is {@code >=} the WHOLE month's gross bruto)
 *       applied to that whole gross — never a marginal bracket walk, no annualization, no PTKP
 *       subtraction, no biaya jabatan. Params: {@code ptkp_category, categories,
 *       no_npwp_surcharge_bp}.
 *   <li>{@code ANNUAL_PROGRESSIVE} — the December / final-month Art-17 true-up: full-year gross
 *       bruto minus biaya jabatan (capped) and deductible social legs minus PTKP relief, walked
 *       through the UU HPP 7/2021 brackets, minus tax already withheld Jan..(month-1) — the result
 *       MAY be negative (an over-withheld refund line). Params: {@code brackets[],
 *       occupational_cost_bp, occupational_cost_cap_annual_minor, no_npwp_surcharge_bp}.
 *   <li>{@code HOURLY_RATE_TABLE} — PP 35/2021 overtime multiplier tiers over a monthly-wage /
 *       divisor hourly rate. Params: {@code monthly_divisor, tiers}. <strong>Dormant in Track P
 *       phase P1</strong> — parsed and validated ({@link StatutoryParams#hourlyRateTable(String)})
 *       but not yet consumed by the calculator; overtime pay is wired in phase P7.
 * </ul>
 */
public enum StatutoryCalcType {
  PROGRESSIVE_BRACKET,
  PERCENTAGE_CEILING,
  RELIEF_TABLE,
  TER_TABLE,
  ANNUAL_PROGRESSIVE,
  HOURLY_RATE_TABLE
}
