package id.co.nativeapp.org.devicecredential.service;

import java.util.UUID;

/**
 * No device credential exists for the given outlet (reset/reveal/delete target). Mapped to {@code
 * 404 Not Found} by {@link
 * id.co.nativeapp.org.devicecredential.config.DeviceCredentialExceptionAdvice}.
 */
public class DeviceCredentialNotFoundException extends RuntimeException {

  public DeviceCredentialNotFoundException(UUID outletId) {
    super("No device credential exists for outlet " + outletId);
  }
}
