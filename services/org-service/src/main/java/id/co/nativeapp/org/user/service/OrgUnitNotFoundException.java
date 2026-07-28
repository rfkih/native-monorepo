package id.co.nativeapp.org.user.service;

import java.util.UUID;

/**
 * The requested org unit does not exist in the caller's company — genuinely absent OR cross-tenant
 * (RLS makes the two indistinguishable; anti-enumeration). Mapped to {@code 404 Not Found} by
 * {@link id.co.nativeapp.org.user.config.UserExceptionAdvice}.
 */
public class OrgUnitNotFoundException extends RuntimeException {

  public OrgUnitNotFoundException(UUID orgUnitId) {
    super("org unit not found: " + orgUnitId);
  }
}
