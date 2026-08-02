package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement request reused an Idempotency-Key that previously settled a DIFFERENT {@code (run,
 * kind)} pair (ADR 0032, Track P phase P5, the {@code AssetDisposalWriter} precedent). The path
 * ({@code runLedgerId}) + body ({@code kind}) are authoritative: silently replaying the other
 * settlement would tell the caller a payment succeeded while never touching the bucket they named.
 * Mapped to 409 {@code payroll-settlement-idempotency-key-conflict}; the client must retry with a
 * fresh key.
 */
public class PayrollSettlementIdempotencyKeyConflictException extends RuntimeException {

  public PayrollSettlementIdempotencyKeyConflictException(
      UUID requestedRunLedgerId,
      String requestedKind,
      UUID replayedRunLedgerId,
      String replayedKind) {
    super(
        "the Idempotency-Key was already used to settle "
            + replayedKind
            + " on run ledger "
            + replayedRunLedgerId
            + ", not the requested "
            + requestedKind
            + " on run ledger "
            + requestedRunLedgerId
            + " — use a fresh key per settlement attempt");
  }
}
