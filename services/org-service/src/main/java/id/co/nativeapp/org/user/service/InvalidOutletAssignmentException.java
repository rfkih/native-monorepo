package id.co.nativeapp.org.user.service;

import java.util.UUID;

/**
 * Thrown when a PUT outlet-assignment request includes an org-unit id that is not an active {@code
 * OUTLET} visible in the caller's tenant.
 *
 * <p>Maps to {@code 400 Bad Request} via {@link
 * id.co.nativeapp.org.user.config.UserExceptionAdvice}.
 *
 * <p>The message includes the offending id (a UUID — stable, non-PII) so the client can surface
 * which entry in the submitted list was invalid. No org-unit name is included.
 */
public class InvalidOutletAssignmentException extends RuntimeException {

  private final UUID orgUnitId;

  public InvalidOutletAssignmentException(UUID orgUnitId, String reason) {
    super("Outlet assignment invalid for org unit " + orgUnitId + ": " + reason);
    this.orgUnitId = orgUnitId;
  }

  /** The org-unit UUID that failed validation. */
  public UUID getOrgUnitId() {
    return orgUnitId;
  }
}
