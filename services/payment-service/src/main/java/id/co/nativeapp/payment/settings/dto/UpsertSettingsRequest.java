package id.co.nativeapp.payment.settings.dto;

/**
 * Owner upsert of a settings scope (ADR 0045). {@code mode} is required (parsed/whitelisted in the
 * writer → 422 on an unknown value). The gateway fields are legal on the COMPANY default scope only
 * (an outlet override carries mode + image, never credentials):
 *
 * <ul>
 *   <li>{@code provider}/{@code environment} — required together whenever any gateway field is
 *       sent;
 *   <li>{@code serverKey} — WRITE-ONLY: {@code null}/blank keeps the previously stored key, a value
 *       replaces it (and refreshes the {@code serverKeyLast4} display trace). It is never echoed
 *       back by any read.
 *   <li>{@code clientKey} — same write-only semantics.
 * </ul>
 */
public record UpsertSettingsRequest(
    String mode, String provider, String environment, String serverKey, String clientKey) {

  /** {@code true} when the request carries any gateway field at all. */
  public boolean hasGatewayFields() {
    return notBlank(provider)
        || notBlank(environment)
        || notBlank(serverKey)
        || notBlank(clientKey);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** Redacted — a request object must never leak a credential into a log line (rule 6). */
  @Override
  public String toString() {
    return "UpsertSettingsRequest[mode="
        + mode
        + ", provider="
        + provider
        + ", environment="
        + environment
        + ", serverKey="
        + (notBlank(serverKey) ? "***REDACTED***" : "(absent)")
        + ", clientKey="
        + (notBlank(clientKey) ? "***REDACTED***" : "(absent)")
        + "]";
  }
}
