package id.co.nativeapp.finance.opening.domain;

/**
 * A replayed {@code Idempotency-Key} whose opening-balance payload DIFFERS from the original (a
 * different as-of date, currency, or line-set — compared by SHA-256 fingerprint) — a client bug
 * surfaced as {@code 409}, never a silent 200 with the original (ADR 0037; the
 * PlatformSettlementWriter idiom).
 */
public class OpeningBalanceIdempotencyKeyConflictException extends RuntimeException {

  public OpeningBalanceIdempotencyKeyConflictException(String message) {
    super(message);
  }
}
