package id.co.nativeapp.payment.settings.domain;

/**
 * Thrown when a delete targets an override/image that does not exist, or the effective static-QRIS
 * image is requested and no scope (outlet or company) carries one (→ 404 via {@code
 * PaymentAdvice}).
 */
public class PaymentSettingsNotFoundException extends RuntimeException {

  public PaymentSettingsNotFoundException(String message) {
    super(message);
  }
}
