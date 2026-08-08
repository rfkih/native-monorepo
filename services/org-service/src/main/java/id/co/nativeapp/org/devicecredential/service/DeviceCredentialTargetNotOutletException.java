package id.co.nativeapp.org.devicecredential.service;

import java.util.UUID;

/**
 * A device-credential create targeted an org unit that is not an {@code OUTLET} (a business unit or
 * a team). Mapped to {@code 400 Bad Request} by {@link
 * id.co.nativeapp.org.devicecredential.config.DeviceCredentialExceptionAdvice}.
 *
 * <p>The message includes the offending id (a UUID — stable, non-PII) and its actual type.
 */
public class DeviceCredentialTargetNotOutletException extends RuntimeException {

  public DeviceCredentialTargetNotOutletException(UUID orgUnitId, String actualType) {
    super(
        "Org unit "
            + orgUnitId
            + " is not an OUTLET (got "
            + actualType
            + ") — a device credential can only be bound to an outlet");
  }
}
