package id.co.nativeapp.employee.payroll.domain;

/**
 * Who bears a pay component. An {@code EMPLOYEE}-borne deduction reduces the employee's net pay; an
 * {@code EMPLOYER}-borne line (e.g. the employer leg of a social-insurance contribution) does NOT
 * reduce net but IS counted as employer labor cost and flows into the labor-cost allocation. This
 * is the distinction step 5 of the gross-to-net pipeline uses to compute net = gross_earnings -
 * sum(EMPLOYEE-bearing deductions).
 */
public enum PayComponentBearer {
  EMPLOYEE,
  EMPLOYER
}
