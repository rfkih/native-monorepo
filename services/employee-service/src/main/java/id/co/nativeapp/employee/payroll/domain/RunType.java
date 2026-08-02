package id.co.nativeapp.employee.payroll.domain;

/**
 * The kind of {@link PayrollRun} (Track P Phase P8, ADR 0035): {@code REGULAR} is the ordinary
 * monthly cadence (unchanged, the only value that existed before P8); {@code THR} is the off-cycle
 * H-7 religious-holiday allowance run (Permenaker 6/2016). Each run type gets its OWN {@code
 * run_seq} sequence per period ({@code payroll_run}'s UNIQUE key is {@code (company_id, period,
 * run_type, run_seq)}) so a THR run and a REGULAR run for the SAME period coexist without
 * superseding each other. Maps 1:1 to the {@code payroll_run.run_type} / {@code
 * pay_component.run_scope} CHECK constraint values — the {@code name()} rides directly onto the
 * wire ({@code PayrollPosted}/{@code LaborCostAllocated}/{@code PayrollLiabilitiesPosted}'s {@code
 * run_type} field, ADR 0032).
 */
public enum RunType {
  REGULAR,
  THR
}
