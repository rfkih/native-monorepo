package id.co.nativeapp.restaurant.config;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The outlet-local time zone — the single place restaurant-service decides what "today" means.
 *
 * <p>Three things need this and must agree: the per-day ingredient stock ledger's bucketing (V42 /
 * V47), the register session's business date (ADR 0036), and the sales-leak report's day and hour
 * attribution (ADR 0074). They were each reaching for their own answer, and the leak report was
 * borrowing the ledger's constant — so a change made for the ledger would silently re-date findings
 * in a report that has nothing to do with ingredients. One named home makes the coupling visible
 * instead of accidental.
 *
 * <p><strong>Fixed {@code Asia/Jakarta} for v1.</strong> It does NOT read the register session's
 * per-session zone override, so for a non-Jakarta outlet these days can diverge from that outlet's
 * register business date. Acceptable while every live outlet is WIB; a per-outlet zone is the
 * additive follow-up, and when it lands EVERY consumer listed above has to move together — which is
 * exactly what this class exists to make obvious.
 */
public final class OutletZone {

  /** The zone every outlet-local date in this service is computed in. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

  private OutletZone() {
    // constants holder
  }

  /** The outlet-local calendar day it is right now. */
  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }
}
