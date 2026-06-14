package id.co.nativeapp.employee.payroll;

/**
 * How an {@link EarningRule} parameterises a non-statutory earning:
 *
 * <ul>
 *   <li>{@code FIXED_AMOUNT} — a fixed Money amount (encrypted at rest as PII in {@code
 *       fixed_amount_enc}).
 *   <li>{@code PERCENT_OF_BASE} — a basis-point rate of the employee's base pay (config, NOT PII).
 *   <li>{@code PER_METRIC_UNIT} — a Money rate per unit of a consumed {@code MetricPublished}
 *       metric (e.g. per wash) read from the {@link MetricInput} projection (config rate, NOT PII).
 * </ul>
 */
public enum EarningParamKind {
  FIXED_AMOUNT,
  PERCENT_OF_BASE,
  PER_METRIC_UNIT
}
