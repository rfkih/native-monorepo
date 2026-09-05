package id.co.nativeapp.restaurant.inventory.projection;

import java.util.UUID;

/**
 * Read projection for one ingredient's movement roll-up over a window ("riwayat stok", V47) — backs
 * the stock-summary query on {@code IngredientStockDayRepository}. Lives in the feature's dedicated
 * {@code projection} package (ArchUnit layer: service + repository only).
 *
 * <p>Carries TOTALS and COUNTS only, never an average: the reader divides by whichever denominator
 * it means (calendar days in the window, or {@link #getDaysWithUsage()}) and formats with
 * locale-aware {@code Intl}. Quantities are integer counts in the ingredient's own unit — never
 * money, never a float.
 */
public interface IngredientStockSummaryView {

  UUID getIngredientId();

  String getName();

  /** The ingredient's display unit (g / ml / pcs / pack) — opaque text, no conversion. */
  String getUnit();

  /** Σ recipe-driven consumption across the window — the numerator of "rata-rata pemakaian". */
  long getTotalUsedQty();

  long getTotalReceivedQty();

  /** SIGNED Σ of manual corrections. Can net to ~0 while {@link #getAdjustmentCount()} is high. */
  long getNetAdjustmentQty();

  long getTotalWasteQty();

  long getReceiptCount();

  /** "Total koreksi manual" — how many separate corrections were made across the window. */
  long getAdjustmentCount();

  /** Days in the window with a ledger row at all (any movement kind). */
  long getDaysWithMovement();

  /** Days in the window that actually consumed this ingredient ({@code qty_used > 0}). */
  long getDaysWithUsage();

  /**
   * The closing figure of the last day IN THE WINDOW that recorded movement — {@code null} when
   * every such row predates V47. Not the ingredient's current stock: later movement may have
   * followed the window. Never read a {@code null} as 0.
   */
  Long getLatestClosingQty();
}
