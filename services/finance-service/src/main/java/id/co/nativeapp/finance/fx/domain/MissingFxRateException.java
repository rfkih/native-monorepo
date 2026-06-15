package id.co.nativeapp.finance.fx.domain;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Currency;

/**
 * Thrown when no FX rate — neither a DIRECT pair nor a USD-pivot triangulation — can be resolved
 * for a requested conversion at the as-of date.
 *
 * <p>This is a FAIL-LOUD: the read endpoint must never invent a rate or silently return the native
 * figures mislabelled as converted. The RFC-7807 advice maps it to {@code 422 Unprocessable Entity}
 * (the request was well-formed but the server cannot satisfy the presentation currency for this
 * period). A missing leg of a triangulated P&amp;L fails the WHOLE conversion atomically — a
 * half-converted figure is never returned.
 */
public final class MissingFxRateException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param from the source currency that could not be converted
   * @param to the target (presentation) currency requested
   * @param asOf the as-of date the rate was looked up at
   */
  public MissingFxRateException(Currency from, Currency to, LocalDate asOf) {
    super(
        "No FX rate (direct or USD-pivot) from "
            + from.getCurrencyCode()
            + " to "
            + to.getCurrencyCode()
            + " effective at "
            + asOf);
  }
}
