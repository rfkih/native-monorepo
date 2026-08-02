package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement was attempted against a run whose LIABILITY dimension is not in a settleable state
 * (ADR 0032, Track P phase P5): either no {@code PayrollLiabilitiesPosted} has landed for the run
 * yet ({@code liability_state IS NULL}), or the run has since been SUPERSEDED by a higher {@code
 * run_seq} of the same {@code (period, run_type)} — settling a superseded run is forbidden, its
 * liability entry has already been reversed. Mapped to 409 (a state conflict, not a missing
 * resource — the run row itself exists and is visible).
 */
public class PayrollLiabilityNotSettleableException extends RuntimeException {

  public PayrollLiabilityNotSettleableException(UUID runLedgerId, String reason) {
    super("payroll run ledger " + runLedgerId + " is not settleable: " + reason);
  }
}
