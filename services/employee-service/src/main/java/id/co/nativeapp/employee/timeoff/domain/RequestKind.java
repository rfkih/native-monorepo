package id.co.nativeapp.employee.timeoff.domain;

/**
 * Discriminates which aggregate a {@link TimeoffRequestEvent} audit row belongs to — the shared
 * table's only per-kind column (ADR 0033 §5).
 */
public enum RequestKind {
  LEAVE,
  OVERTIME
}
