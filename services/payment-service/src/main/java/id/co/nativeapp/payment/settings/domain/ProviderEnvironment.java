package id.co.nativeapp.payment.settings.domain;

/**
 * Which Midtrans environment the company's credentials belong to (ADR 0045): the sandbox for
 * drills/UAT, production for live money. Selects the client's base URL at charge time.
 */
public enum ProviderEnvironment {
  SANDBOX,
  PRODUCTION;

  /**
   * Parses a request-supplied environment string.
   *
   * @throws SettingsValidationException if the value is not a known environment (→ 422)
   */
  public static ProviderEnvironment parse(String value) {
    if (value == null || value.isBlank()) {
      throw new SettingsValidationException("environment is required (SANDBOX or PRODUCTION)");
    }
    try {
      return ProviderEnvironment.valueOf(value.strip());
    } catch (IllegalArgumentException e) {
      throw new SettingsValidationException("Unknown environment: must be SANDBOX or PRODUCTION");
    }
  }
}
