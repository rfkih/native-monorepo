package id.co.nativeapp.restaurant.inventory.projection;

import java.time.LocalDate;

/**
 * Read projection for ONE day of one ingredient's stock ledger ("riwayat stok harian", V47) — backs
 * the daily-ledger query on {@code IngredientStockDayRepository}. Lives in the feature's dedicated
 * {@code projection} package (ArchUnit layer: service + repository only).
 *
 * <p>Quantities are integer counts in the ingredient's own unit — never money, never a float.
 */
public interface IngredientStockDayView {

  LocalDate getStockDate();

  /** Recipe-driven consumption at sale time, as requested by the recipe (not floored at stock). */
  long getQtyUsed();

  /** Stock that came in: goods receipts plus manual "tambah stok". Never negative. */
  long getReceivedQty();

  /** SIGNED net manual correction — opname variance plus manual "set stok". Negative = short. */
  long getAdjustmentQty();

  /** Recorded waste / staff meals. Always 0 until the waste-log feature lands. */
  long getWasteQty();

  int getReceiptCount();

  /** How many separate manual corrections happened that day. */
  int getAdjustmentCount();

  /**
   * Stock immediately after the day's last movement, or {@code null} for a pre-V47 row where it is
   * genuinely unknown. Never read a {@code null} as 0.
   */
  Long getClosingQty();
}
