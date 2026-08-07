package id.co.nativeapp.payment.settings.domain;

/**
 * Thrown for an invalid payment-settings request — an unknown mode/provider/environment value or an
 * invalid field combination (e.g. credentials on an outlet override) (→ 422 via {@code
 * PaymentAdvice}). The message never carries a credential value.
 */
public class SettingsValidationException extends RuntimeException {

  public SettingsValidationException(String message) {
    super(message);
  }
}
