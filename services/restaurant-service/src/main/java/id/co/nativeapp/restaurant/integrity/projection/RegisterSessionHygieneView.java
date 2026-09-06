package id.co.nativeapp.restaurant.integrity.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for one register session in the reported window — the raw material for the
 * closing-hygiene checks, which are derived in the service layer rather than in SQL because they
 * are statements about a SEQUENCE of closes (a run of suspiciously exact counts) that reads far
 * more clearly, and tests far more precisely, as a fold over ordered rows.
 *
 * <p>Backs {@code SalesIntegrityRepository.findSessionsInWindow}, ordered by {@code opened_at}.
 */
public interface RegisterSessionHygieneView {

  UUID getSessionId();

  LocalDate getBusinessDate();

  Instant getOpenedAt();

  /** {@code null} while the session is still OPEN. */
  Instant getClosedAt();

  /** {@code OPEN | CLOSED}. */
  String getStatus();

  /** SIGNED counted − expected cash: negative = short, positive = over. {@code null} while OPEN. */
  Long getOverShortMinor();

  String getCurrency();

  /** The actor that closed the session (the last write to the row), or the opener while OPEN. */
  String getClosedBy();
}
