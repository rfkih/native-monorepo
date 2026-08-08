package id.co.nativeapp.org.devicecredential.service;

import java.util.UUID;

/**
 * A device-credential create was attempted for an outlet that already has one. Mapped to {@code 409
 * Conflict} by {@link id.co.nativeapp.org.devicecredential.config.DeviceCredentialExceptionAdvice}.
 *
 * <p>Thrown both by the pre-check (before any Keycloak call) and, defensively, by the {@code
 * UNIQUE(company_id, org_unit_id)} constraint (V11) on a concurrent create race.
 */
public class DeviceCredentialAlreadyExistsException extends RuntimeException {

  public DeviceCredentialAlreadyExistsException(UUID outletId) {
    super("A device credential already exists for outlet " + outletId);
  }
}
