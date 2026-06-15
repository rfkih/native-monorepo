package id.co.nativeapp.finance.consolidation.domain;

import java.io.Serial;

/**
 * Thrown when a group close is attempted at a {@code close_run_seq} that is not strictly higher
 * than the current active close for the period (P3d SEAM 3a) — a stale or duplicate close attempt
 * that would reverse a NEWER close. The close refuses rather than supersede a more-recent
 * consolidation. (An exact-same-seq re-run is handled as an idempotent no-op upstream; this guards
 * a lower-or-equal seq racing in after a higher close has already committed.)
 */
public class StaleCloseRunException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public StaleCloseRunException(String message) {
    super(message);
  }
}
