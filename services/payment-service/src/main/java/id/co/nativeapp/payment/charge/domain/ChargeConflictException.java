package id.co.nativeapp.payment.charge.domain;

/**
 * Thrown for a create/transition conflict — → 409: the effective mode is not GATEWAY, no
 * credentials are configured, a replayed Idempotency-Key carries a different payload, or a
 * transition targets a terminal charge.
 */
public class ChargeConflictException extends RuntimeException {

  public ChargeConflictException(String message) {
    super(message);
  }
}
