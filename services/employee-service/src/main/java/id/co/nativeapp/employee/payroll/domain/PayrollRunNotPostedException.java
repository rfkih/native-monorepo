package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * The net-pay bank file was requested for a run that is not (yet) POSTED (Track P phase P5) — a
 * DRAFT/CALCULATING/CALCULATED/FAILED run has no final, disbursable net-pay figures. Mapped to 409
 * (a state conflict: the run exists and is visible, but is not in the right lifecycle state).
 */
public class PayrollRunNotPostedException extends RuntimeException {

  public PayrollRunNotPostedException(UUID runId, String status) {
    super("payroll run " + runId + " is not POSTED (status=" + status + ") — no bank file yet");
  }
}
