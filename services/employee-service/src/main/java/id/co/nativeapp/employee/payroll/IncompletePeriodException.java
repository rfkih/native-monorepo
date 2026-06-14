package id.co.nativeapp.employee.payroll;

import java.util.List;
import java.util.UUID;

/**
 * Raised when a payroll run is asked to calculate/post a period that is NOT yet complete — at least
 * one expected source business_id has not sealed the period (ARCHITECTURE.md §4 — no running on
 * partial data). Mapped to {@code 409 Conflict} (the request is well-formed but conflicts with the
 * current completeness state). The message names only business-unit ids (UUIDs), never PII (rule
 * 6).
 */
public class IncompletePeriodException extends RuntimeException {

  public IncompletePeriodException(String period, List<UUID> unsealed) {
    super(
        "Period "
            + period
            + " is not complete: "
            + unsealed.size()
            + " expected source(s) have not sealed yet "
            + unsealed
            + "; payroll cannot run on partial data");
  }
}
