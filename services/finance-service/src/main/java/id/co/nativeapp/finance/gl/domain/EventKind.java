package id.co.nativeapp.finance.gl.domain;

/**
 * The event kind driving an auto-posting — used as the key into the {@code posting_template}
 * reference table. Values align with the event names in the event catalog and the seeds in V13.
 */
public enum EventKind {
  SALE,
  EXPENSE,
  LABOR,
  /** Full reversal of a captured sale before settlement — contra of {@link #SALE}. */
  SALE_VOID,
  /**
   * Partial or full refund of a captured sale after settlement — proportional contra of {@link
   * #SALE}.
   */
  SALE_REFUND
}
