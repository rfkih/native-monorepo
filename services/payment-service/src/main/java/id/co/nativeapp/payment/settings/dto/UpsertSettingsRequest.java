package id.co.nativeapp.payment.settings.dto;

/**
 * Owner upsert of a settings scope (ADR 0045, per-environment amendment). {@code mode} is required
 * (parsed/whitelisted in the writer → 422 on an unknown value). The gateway fields are legal on the
 * COMPANY default scope only (an outlet override carries mode + image, never credentials):
 *
 * <ul>
 *   <li>{@code provider} — the PSP (MIDTRANS); required whenever any gateway field is sent.
 *   <li>{@code activeEnvironment} — SANDBOX or PRODUCTION, the slot the till + webhook use. The
 *       writer only activates an environment whose slot holds a server key (→ 422 otherwise), so an
 *       environment can never be activated against another environment's key.
 *   <li>{@code sandboxServerKey}/{@code sandboxClientKey}/{@code productionServerKey}/{@code
 *       productionClientKey} — WRITE-ONLY per-environment credentials: {@code null}/blank keeps
 *       that slot's previously stored value, a value replaces it (and refreshes the slot's {@code
 *       last4}). Never echoed back by any read.
 * </ul>
 */
public record UpsertSettingsRequest(
    String mode,
    String provider,
    String activeEnvironment,
    String sandboxServerKey,
    String sandboxClientKey,
    String productionServerKey,
    String productionClientKey) {

  /** {@code true} when the request carries any gateway field at all. */
  public boolean hasGatewayFields() {
    return notBlank(provider)
        || notBlank(activeEnvironment)
        || notBlank(sandboxServerKey)
        || notBlank(sandboxClientKey)
        || notBlank(productionServerKey)
        || notBlank(productionClientKey);
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
        + ", activeEnvironment="
        + activeEnvironment
        + ", sandboxServerKey="
        + redacted(sandboxServerKey)
        + ", sandboxClientKey="
        + redacted(sandboxClientKey)
        + ", productionServerKey="
        + redacted(productionServerKey)
        + ", productionClientKey="
        + redacted(productionClientKey)
        + "]";
  }

  private static String redacted(String value) {
    return notBlank(value) ? "***REDACTED***" : "(absent)";
  }
}
