package id.co.nativeapp.restaurant.inventory.dto;

import java.util.UUID;

/**
 * One ingredient's stock movement roll-up over the requested window ("riwayat stok", V47) — element
 * of {@code GET /api/v1/ingredients/stock-history}.
 *
 * <p>Quantities are integer counts in the ingredient's own unit (never money, never a float).
 * Ingredients that did not move at all in the window are absent from the list.
 *
 * <p><strong>No average is computed here.</strong> The client divides {@code totalUsedQty} by
 * whichever denominator it means — calendar days in the window for "rata-rata pemakaian per hari",
 * or {@code daysWithUsage} for "rata-rata pada hari terpakai" — and formats the result with
 * locale-aware {@code Intl}. Rounding is a presentation decision, so it is made where the
 * presentation is.
 *
 * @param ingredientId the ingredient
 * @param name the ingredient's name at read time
 * @param unit the ingredient's display unit (g / ml / pcs / pack)
 * @param totalUsedQty the window's recipe-driven consumption
 * @param totalReceivedQty the window's stock in (receipts + manual "tambah stok")
 * @param netAdjustmentQty the window's SIGNED net manual correction (negative = counted short)
 * @param totalWasteQty recorded waste — always 0 until the waste-log feature lands
 * @param receiptCount how many separate receives landed in the window
 * @param adjustmentCount "total koreksi manual" — how many separate corrections were made
 * @param daysWithMovement days in the window with any movement at all
 * @param daysWithUsage days in the window that consumed this ingredient
 * @param latestClosingQty stock after the last movement IN the window, or {@code null} when unknown
 *     (every such row predates V47) — never read a {@code null} as 0
 */
public record IngredientStockSummaryResponse(
    UUID ingredientId,
    String name,
    String unit,
    long totalUsedQty,
    long totalReceivedQty,
    long netAdjustmentQty,
    long totalWasteQty,
    long receiptCount,
    long adjustmentCount,
    long daysWithMovement,
    long daysWithUsage,
    Long latestClosingQty) {}
