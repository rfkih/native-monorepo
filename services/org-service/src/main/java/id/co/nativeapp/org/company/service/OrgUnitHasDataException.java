package id.co.nativeapp.org.company.service;

import java.util.UUID;

/**
 * Thrown when a permanent delete is attempted on an org unit (or one of its descendants) that is
 * NOT empty — it has, or ever had, an assigned login (an unassigned login keeps its closed row), so
 * a cashier could have rung sales there. Such a unit must be DEACTIVATED (preserving history),
 * never hard-deleted.
 *
 * <p>Mapped to HTTP {@code 409 Conflict} by {@link
 * id.co.nativeapp.org.company.config.OrgUnitExceptionAdvice}.
 */
public class OrgUnitHasDataException extends RuntimeException {

  private final UUID orgUnitId;

  public OrgUnitHasDataException(UUID orgUnitId) {
    super(
        "Org unit "
            + orgUnitId
            + " has (or previously had) an assigned login and cannot be permanently deleted");
    this.orgUnitId = orgUnitId;
  }

  /** The org unit that still has data (for logging; non-PII). */
  public UUID getOrgUnitId() {
    return orgUnitId;
  }
}
