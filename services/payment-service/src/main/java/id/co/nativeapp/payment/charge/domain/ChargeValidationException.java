package id.co.nativeapp.payment.charge.domain;

/**
 * Thrown for an invalid charge request — → 422: a non-IDR currency (QRIS is IDR-only, ADR 0045), an
 * unknown vertical, or a non-positive amount.
 */
public class ChargeValidationException extends RuntimeException {

  public ChargeValidationException(String message) {
    super(message);
  }
}
