package id.co.nativeapp.restaurant.register.domain;

import java.util.UUID;

/**
 * Opening a drawer at an outlet that already has an OPEN session (ADR 0036 — at most one per
 * outlet, enforced by the {@code uq_crs_one_open_per_outlet} partial unique). Mapped to {@code 409
 * Conflict} ({@code register-session-already-open}) by {@code RegisterAdvice}.
 */
public class RegisterSessionAlreadyOpenException extends RuntimeException {

  public RegisterSessionAlreadyOpenException(UUID businessId) {
    super("outlet " + businessId + " already has an OPEN register session");
  }
}
