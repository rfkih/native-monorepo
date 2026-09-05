package id.co.nativeapp.restaurant.inventory.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ingredient's stock movement on one outlet-local calendar day ("riwayat stok harian", V47) —
 * every way the figure moved that day, bucketed by kind, plus where it landed.
 *
 * <p>V42 shipped this table as {@code ingredient_usage_day} with a single {@code qty_used} bucket
 * written by the per-sale depletion. V47 renamed and widened it into the full daily ledger: sales
 * consumption ({@link #qtyUsed}), stock in ({@link #receivedQty}), signed manual corrections
 * ({@link #adjustmentQty}), recorded waste ({@link #wasteQty}, reserved), the two counters an owner
 * reads directly ({@link #receiptCount}, {@link #adjustmentCount}) and the day's {@link
 * #closingQty}.
 *
 * <p><strong>A day with no movement has no row.</strong> Readers take the opening balance from the
 * most recent EARLIER row's {@code closingQty} — a gap is a flat line, not a hole. {@code
 * closingQty} is {@code null} on pre-V47 rows, where it is genuinely unknown; never read that as 0.
 *
 * <p>The entity exists to anchor the repository and mirror the schema; ALL writes go through the
 * repository's native {@code INSERT … ON CONFLICT} (a JPA save could not express the atomic
 * per-bucket increment), so the {@code @Version} column is a plain counter here, not an optimistic
 * lock in use. Quantities are integer counts in the ingredient's own unit (never money). Extends
 * {@link Auditable} (rules 4 + 5, V42 RLS policy carried through the V47 rename).
 */
@Entity
@Table(name = "ingredient_stock_day")
public class IngredientStockDay extends Auditable {

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  @Column(name = "stock_date", nullable = false, updatable = false)
  private LocalDate stockDate;

  /**
   * Recipe-driven depletion at sale time, as REQUESTED by the recipe — deliberately NOT floored at
   * the available stock (the stock figure floors separately, in {@code ingredient.stock_qty}). So
   * this can exceed what was physically on hand, and that excess is itself a signal.
   */
  @Column(name = "qty_used", nullable = false)
  private long qtyUsed;

  /** Stock that came IN that day: goods receipts plus manual "tambah stok". Never negative. */
  @Column(name = "received_qty", nullable = false)
  private long receivedQty;

  /**
   * SIGNED net manual correction that day: stock-opname variance ({@code counted − system}) plus
   * any manual "set stok" delta. Negative = the count came up short. The only signed bucket.
   */
  @Column(name = "adjustment_qty", nullable = false)
  private long adjustmentQty;

  /**
   * Recorded waste / spoilage / staff meals. RESERVED — always 0 until the waste-log feature lands;
   * the column exists now so the ledger shape does not change under readers later.
   */
  @Column(name = "waste_qty", nullable = false)
  private long wasteQty;

  /** How many separate receive events landed that day (not the quantity). */
  @Column(name = "receipt_count", nullable = false)
  private int receiptCount;

  /**
   * How many separate manual corrections were made that day — "berapa kali stok dikoreksi manual".
   * A high count is worth a look regardless of {@link #adjustmentQty}, which can net to nearly
   * zero.
   */
  @Column(name = "adjustment_count", nullable = false)
  private int adjustmentCount;

  /**
   * {@code ingredient.stock_qty} immediately after the day's LAST recorded movement, or {@code
   * null} for a pre-V47 row where it is genuinely unknown. Never read a {@code null} as 0.
   */
  @Column(name = "closing_qty")
  private Long closingQty;

  protected IngredientStockDay() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public UUID getIngredientId() {
    return ingredientId;
  }

  public LocalDate getStockDate() {
    return stockDate;
  }

  public long getQtyUsed() {
    return qtyUsed;
  }

  public long getReceivedQty() {
    return receivedQty;
  }

  public long getAdjustmentQty() {
    return adjustmentQty;
  }

  public long getWasteQty() {
    return wasteQty;
  }

  public int getReceiptCount() {
    return receiptCount;
  }

  public int getAdjustmentCount() {
    return adjustmentCount;
  }

  /** The day's closing stock, or {@code null} when unknown (pre-V47 row). */
  public Long getClosingQty() {
    return closingQty;
  }
}
