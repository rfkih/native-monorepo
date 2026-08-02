package id.co.nativeapp.employee.timeoff.domain;

/**
 * The shared lifecycle for BOTH {@link LeaveRequest} and {@link OvertimeEntry} (ADR 0033 §3, the
 * AP-Bill/expense-claim idiom, minus the expense-claim's DRAFT stage — a time-off request is
 * created directly as {@link #SUBMITTED}, since there is no draft-editing step in v1): {@code
 * SUBMITTED -> APPROVED | REJECTED}; cancel only from {@code SUBMITTED}. A decision (approve or
 * reject) stamps {@code decided_by}/{@code decided_at}; a rejection additionally requires a note
 * (the refuse-requires-comment idiom).
 */
public enum TimeoffStatus {
  SUBMITTED,
  APPROVED,
  REJECTED,
  CANCELLED
}
