package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement was attempted against a {@code (run, kind)} that is ALREADY settled under a
 * DIFFERENT Idempotency-Key (a genuine second settlement attempt, not a retry — a retry replays via
 * the stored key instead, see {@code PayrollSettlementWriter}). Mapped to 409 {@code
 * payroll-settlement-already-settled}; the client must not double-pay — a settlement is one-shot
 * per bucket (ADR 0032, Track P phase P5).
 */
public class PayrollSettlementAlreadySettledException extends RuntimeException {

  public PayrollSettlementAlreadySettledException(UUID runLedgerId, String kind) {
    super(
        "the "
            + kind
            + " bucket of payroll run ledger "
            + runLedgerId
            + " is already settled — a settlement cannot be repeated");
  }
}
