package id.co.nativeapp.payment.settings.domain;

/**
 * The payment-gateway provider a company's GATEWAY mode charges through (ADR 0045). One value
 * today; the {@code QrisGatewayPort} seam keeps further providers a new adapter, not a redesign.
 */
public enum PspProvider {
  MIDTRANS;

  /**
   * Parses a request-supplied provider string.
   *
   * @throws SettingsValidationException if the value is not a known provider (→ 422)
   */
  public static PspProvider parse(String value) {
    if (value == null || value.isBlank()) {
      throw new SettingsValidationException("provider is required (MIDTRANS)");
    }
    try {
      return PspProvider.valueOf(value.strip());
    } catch (IllegalArgumentException e) {
      throw new SettingsValidationException("Unknown provider: must be MIDTRANS");
    }
  }
}
