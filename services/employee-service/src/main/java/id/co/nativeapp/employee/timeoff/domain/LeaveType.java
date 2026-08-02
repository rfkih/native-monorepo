package id.co.nativeapp.employee.timeoff.domain;

/**
 * The kind of a {@link LeaveRequest} (ADR 0033 §2). Drives whether a request counts against the
 * derived {@link LeaveBalance} ({@link #ANNUAL} only — {@link #UNPAID} and {@link #SICK} skip the
 * balance entirely) and, in Track P Phase P7, which payslip earning it feeds.
 */
public enum LeaveType {

  /**
   * Paid annual leave — 12 days/year, UU 13/2003 Art 79. Approving decrements the derived balance.
   */
  ANNUAL,

  /** Unpaid leave — prorates pay in P7 (base ÷ the tenant's {@link WorkCalendar} divisor). */
  UNPAID,

  /** Paid sick leave — paid-full v1 (the 100/75/50/25% prolonged-illness schedule is deferred). */
  SICK
}
