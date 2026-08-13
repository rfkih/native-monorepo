package id.co.nativeapp.payment.settings.dto;

/**
 * Owner request to verify a Midtrans key against the provider WITHOUT saving or charging (ADR 0045
 * amendment — "Test connection"). {@code environment} selects which base URL to probe (SANDBOX /
 * PRODUCTION). {@code serverKey} is the key to test; when {@code null}/blank the service probes the
 * key already stored for that environment's slot. The key is never logged or echoed back (rule 6).
 */
public record GatewayVerifyRequest(String environment, String serverKey) {

  /** Redacted — a request object must never leak a credential into a log line (rule 6). */
  @Override
  public String toString() {
    boolean present = serverKey != null && !serverKey.isBlank();
    return "GatewayVerifyRequest[environment="
        + environment
        + ", serverKey="
        + (present ? "***REDACTED***" : "(absent)")
        + "]";
  }
}
