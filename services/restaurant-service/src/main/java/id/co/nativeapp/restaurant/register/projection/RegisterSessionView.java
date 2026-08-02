package id.co.nativeapp.restaurant.register.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for register sessions (ADR 0036) — backs the native current-session and history
 * queries on {@code RegisterSessionRepository}. Close-only columns are null while OPEN.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface RegisterSessionView {

  UUID getId();

  UUID getBusinessId();

  String getStatus();

  LocalDate getBusinessDate();

  Instant getOpenedAt();

  long getOpeningFloatMinor();

  String getCurrency();

  Instant getClosedAt();

  Long getCashSalesMinor();

  Long getCashRefundsMinor();

  Long getExpectedCashMinor();

  Long getCountedCashMinor();

  Long getOverShortMinor();
}
