package id.co.nativeapp.finance.opening.domain;

/**
 * A company that already recorded its opening balance sheet tried to record it again under a
 * DIFFERENT Idempotency-Key (ADR 0037). Opening balances are once-only — there is no amend/revise
 * flow — so this surfaces as {@code 409}, never a second posting. (A same-key retry replays 200; a
 * same-key different-payload retry is {@link OpeningBalanceIdempotencyKeyConflictException}.)
 */
public class OpeningBalanceAlreadyRecordedException extends RuntimeException {

  public OpeningBalanceAlreadyRecordedException(String message) {
    super(message);
  }
}
