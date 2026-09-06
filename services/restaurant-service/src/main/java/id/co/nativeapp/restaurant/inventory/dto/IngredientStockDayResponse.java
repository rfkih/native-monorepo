package id.co.nativeapp.restaurant.inventory.dto;

import java.time.LocalDate;

/**
 * One day of one ingredient's stock ledger ("riwayat stok harian", V47) — element of {@code GET
 * /api/v1/ingredients/{id}/stock-history}.
 *
 * <p>Quantities are integer counts in the ingredient's own unit (never money, never a float). Days
 * with no movement are ABSENT from the list rather than returned as zero rows: the client carries
 * the previous day's {@code closingQty} forward across the gap, which is what actually happened.
 *
 * @param stockDate the outlet-local calendar day (Asia/Jakarta attribution)
 * @param qtyUsed recipe-driven consumption, as requested by the recipe — not floored at the stock
 *     actually on hand, so it can exceed what the system believed existed
 * @param receivedQty stock that came in that day (receipts + manual "tambah stok")
 * @param adjustmentQty SIGNED net manual correction that day (negative = counted short)
 * @param wasteQty recorded waste — always 0 until the waste-log feature lands
 * @param receiptCount how many separate receives landed that day
 * @param adjustmentCount how many separate manual corrections were made that day
 * @param closingQty stock after the day's last movement, or {@code null} for a pre-V47 row where it
 *     is genuinely unknown — never read a {@code null} as 0
 */
public record IngredientStockDayResponse(
    LocalDate stockDate,
    long qtyUsed,
    long receivedQty,
    long adjustmentQty,
    long wasteQty,
    int receiptCount,
    int adjustmentCount,
    Long closingQty) {}
