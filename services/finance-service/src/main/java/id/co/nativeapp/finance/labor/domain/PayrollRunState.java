package id.co.nativeapp.finance.labor.domain;

/**
 * The lifecycle state of a {@link PayrollRunLedger} control row (#23).
 *
 * <p>State machine: a run is {@link #PENDING} while its buckets accumulate and its {@code
 * PayrollPosted} has not yet reconciled it; on {@code PayrollPosted} arrival it becomes {@link
 * #RECONCILED} (sum matches the control total) or {@link #RECONCILE_FAILED} (mismatch — loud, money
 * stays on the books, the period is not presented as final). Any run becomes {@link #SUPERSEDED}
 * when a higher-{@code run_seq} run for the same {@code (company, period)} reverses and reposts it.
 *
 * <p>{@link #SUPERSEDED} and {@link #CURRENCY_MISMATCH} are TERMINAL: once entered, a later event
 * cannot transition the run away from them (see {@link PayrollRunLedger#transitionTo}).
 */
public enum PayrollRunState {
  /** Buckets are accumulating; the run's {@code PayrollPosted} has not yet reconciled it. */
  PENDING,
  /** The bucket sum matched the {@code PayrollPosted} labor control total. */
  RECONCILED,
  /** The bucket sum did NOT match the control total (held back from being presented as final). */
  RECONCILE_FAILED,
  /** A higher-{@code run_seq} run for the same {@code (company, period)} superseded this one. */
  SUPERSEDED,
  /**
   * A bucket diverged from the period's established currency (no FX in scope, #23). TERMINAL and
   * STICKY: once any divergent-currency cost was observed-and-dropped, the run can NEVER self-heal
   * to {@link #RECONCILED} — a later in-currency bucket completing the control sum must not mask
   * that a cost in another currency was seen and held back. The divergent bucket is not posted; the
   * period is held back from being presented as final.
   */
  CURRENCY_MISMATCH
}
