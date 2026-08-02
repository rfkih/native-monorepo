package id.co.nativeapp.finance.platform.domain;

/**
 * A replayed {@code Idempotency-Key} whose settlement payload DIFFERS from the original (channel,
 * gross, net, or currency) — a client bug surfaced as {@code 409}, never a silent 200 with the
 * original settlement (ADR 0036; the PayrollSettlementWriter idiom).
 */
public class PlatformSettlementIdempotencyKeyConflictException extends RuntimeException {

  public PlatformSettlementIdempotencyKeyConflictException(String message) {
    super(message);
  }
}
