package id.co.nativeapp.restaurant.inventory.domain;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The calendar-day attribution for the per-day ingredient stock ledger ({@link IngredientStockDay},
 * V42/V47) — one place, so every writer that touches a ledger row buckets it into the SAME day.
 *
 * <p>Lives in {@code domain} rather than on any one writer because both the {@code recipe}
 * feature's depletion writer and the {@code inventory} feature's receive/correction writers need
 * it, and the ArchUnit layered rule lets a service reach {@code domain} but never another feature's
 * {@code service}.
 *
 * <p><strong>Fixed {@code Asia/Jakarta}, carried over verbatim from V42's depletion
 * writer.</strong> It does NOT read the outlet's register-session business zone (which supports a
 * per-session override, ADR 0036), so for a non-Jakarta outlet the ledger day can diverge from that
 * outlet's register business date. Acceptable for v1 (every live outlet is WIB); a per-outlet
 * ledger zone is the additive follow-up. Attribution is by WHEN THE MOVEMENT RUNS: an offline sale
 * replayed the next day counts toward the replay day, matching when the stock figure actually
 * moved. The console read key pins the same day, so read and write always agree.
 */
public final class StockLedgerDay {

  /** The v1 zone every ledger row is bucketed by. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

  private StockLedgerDay() {
    // constants holder
  }

  /** The ledger day a movement happening right now belongs to. */
  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }
}
