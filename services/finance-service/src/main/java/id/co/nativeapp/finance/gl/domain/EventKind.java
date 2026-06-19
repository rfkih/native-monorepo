package id.co.nativeapp.finance.gl.domain;

/**
 * The event kind driving an auto-posting — used as the key into the {@code posting_template}
 * reference table. Values align with the event names in the event catalog and the seeds in V13.
 */
public enum EventKind {
  SALE,
  EXPENSE,
  LABOR
}
