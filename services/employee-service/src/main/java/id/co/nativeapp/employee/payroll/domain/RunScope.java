package id.co.nativeapp.employee.payroll.domain;

/**
 * Which {@link RunType}(s) a {@link PayComponent} participates in (Track P Phase P8, ADR 0035).
 * {@code ALL} (the default — every pre-P8 component, e.g. {@code PPH21}, keeps resolving on every
 * run type unchanged) | {@code REGULAR} (statutory spec: BASE, allowances, commission, overtime,
 * unpaid leave, expense reimbursement, and EVERY BPJS leg — BPJS never applies to a THR run) |
 * {@code THR} (the THR earning component only). {@link PayrollRunWriter} resolves ONLY {@code
 * run_scope}-matching components for a given run's {@link RunType} — see {@link
 * #appliesTo(RunType)}.
 */
public enum RunScope {
  ALL,
  REGULAR,
  THR;

  /** Whether a component carrying this scope resolves on a run of {@code runType}. */
  public boolean appliesTo(RunType runType) {
    return this == ALL || this.name().equals(runType.name());
  }
}
