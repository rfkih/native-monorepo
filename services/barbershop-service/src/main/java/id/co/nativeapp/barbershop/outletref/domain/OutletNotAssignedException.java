package id.co.nativeapp.barbershop.outletref.domain;

import java.util.UUID;

/**
 * Thrown when an attendant attempts to ring a ticket at an outlet they are not assigned to (Phase 5
 * enforcement policy ported from carwash-service's {@code outletref} feature). The HTTP response is
 * a {@code 403} RFC-7807 {@code ProblemDetail} with the stable type URI {@code
 * https://errors.nativeapp.id/outlet-not-assigned}.
 *
 * <p>Owner and manager roles bypass this check entirely — only cashiers/attendants are subject to
 * it. Lives in the {@code outletref} feature (the local read model + guard that own outlet-scoping
 * enforcement), not in the ticket/checkout feature.
 */
public class OutletNotAssignedException extends RuntimeException {

  /** Stable RFC-7807 problem type URI for this error — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/outlet-not-assigned";

  private final String userId;
  private final UUID orgUnitId;

  public OutletNotAssignedException(String userId, UUID orgUnitId) {
    super(
        "User "
            + userId
            + " is not assigned to outlet "
            + orgUnitId
            + "; access denied (default-closed)");
    this.userId = userId;
    this.orgUnitId = orgUnitId;
  }

  public String getUserId() {
    return userId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }
}
