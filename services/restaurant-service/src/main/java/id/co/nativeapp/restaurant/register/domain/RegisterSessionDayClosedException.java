package id.co.nativeapp.restaurant.register.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Opening a drawer at an outlet that already has a register session (OPEN or CLOSED) for the
 * requested business day (ADR 0038 — the daily close is day-final: at most one session per outlet
 * per {@code business_date}, enforced by the {@code uq_crs_one_session_per_outlet_day} unique).
 * Mapped to {@code 409 Conflict} ({@code register-session-day-closed}) by {@code RegisterAdvice}.
 */
public class RegisterSessionDayClosedException extends RuntimeException {

  public RegisterSessionDayClosedException(UUID businessId, LocalDate businessDate) {
    super(
        "outlet "
            + businessId
            + " already has a register session for business day "
            + businessDate);
  }
}
