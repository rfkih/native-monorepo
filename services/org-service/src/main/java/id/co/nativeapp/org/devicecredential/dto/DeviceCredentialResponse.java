package id.co.nativeapp.org.devicecredential.dto;

import java.util.UUID;

/**
 * The device-credential wire response: {@code outletId}, {@code username}, and the PLAINTEXT {@code
 * password} — returned by create (once), reset (once), and reveal (owner/manager re-provisioning).
 *
 * <p><strong>Credential hygiene (rule 6).</strong> {@link #toString()} is redacted so an accidental
 * {@code log.info("res={}", response)} — e.g. from a framework/AOP trace — can never leak the
 * password. Callers must not log this DTO's fields directly either.
 *
 * @param outletId the outlet (org_unit) this device is bound to
 * @param username the device login's Keycloak username
 * @param password the device login's password, PLAINTEXT — NEVER log this value
 */
public record DeviceCredentialResponse(UUID outletId, String username, String password) {

  @Override
  public String toString() {
    return "DeviceCredentialResponse[outletId="
        + outletId
        + ", username="
        + username
        + ", password=***REDACTED***]";
  }
}
