package id.co.nativeapp.employee.payroll;

/**
 * Whether a {@link PayComponent} adds to pay (an {@code EARNING}) or subtracts from it (a {@code
 * DEDUCTION}). The kind, together with the {@link PayComponentBearer bearer}, decides how a
 * produced {@link PayslipLine} affects net pay and labor cost in the gross-to-net pipeline.
 */
public enum PayComponentKind {
  EARNING,
  DEDUCTION
}
