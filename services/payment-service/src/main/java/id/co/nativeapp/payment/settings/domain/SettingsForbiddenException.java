package id.co.nativeapp.payment.settings.domain;

/**
 * Thrown when a caller whose PRESENT roles lack {@code owner} attempts a payment-settings write (→
 * 403 via {@code PaymentAdvice}). Defense in depth behind the gateway's owner-only route: the
 * fleet's dev-recipe trust means a caller with NO roles header (consumer threads, service-layer
 * tests, the dev profile) is not rejected here.
 */
public class SettingsForbiddenException extends RuntimeException {

  public SettingsForbiddenException() {
    super("Payment settings can only be changed by the company owner.");
  }
}
