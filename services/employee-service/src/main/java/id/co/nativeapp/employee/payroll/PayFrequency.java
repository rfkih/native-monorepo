package id.co.nativeapp.employee.payroll;

/**
 * The cadence a {@link CompensationPackage}'s base pay is expressed at. Only {@code MONTHLY} is
 * modelled in this slice (the period is a {@code YYYY-MM} month); the enum exists so a future
 * weekly/bi-weekly cadence is an additive change, not a schema change.
 */
public enum PayFrequency {
  MONTHLY
}
