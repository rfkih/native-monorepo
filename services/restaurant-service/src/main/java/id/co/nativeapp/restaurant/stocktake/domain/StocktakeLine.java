package id.co.nativeapp.restaurant.stocktake.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One counted item within a {@link Stocktake} (ADR 0038 phase 3) — the audit trail behind the
 * parent's aggregate {@code shrinkageMinor}. {@code varianceQty = countedQty − systemQty} (positive
 * = found more, negative = shrinkage); {@code varianceValueMinor = varianceQty × unitCostMinor}, or
 * {@code 0} when the item carries no cost (no ledger impact).
 *
 * <p>Extends {@link Auditable} (rules 4 + 5), covered by the V28 RLS policy.
 */
@Entity
@Table(name = "stocktake_line")
public class StocktakeLine extends Auditable {

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "stocktake_id", nullable = false, updatable = false)
  private UUID stocktakeId;

  @Column(name = "menu_item_id", nullable = false, updatable = false)
  private UUID menuItemId;

  @Column(name = "system_qty", nullable = false, updatable = false)
  private int systemQty;

  @Column(name = "counted_qty", nullable = false, updatable = false)
  private int countedQty;

  @Column(name = "variance_qty", nullable = false, updatable = false)
  private int varianceQty;

  /** The item's unit cost at count time; {@code null} when the item had no cost (no GL impact). */
  @Column(name = "unit_cost_minor")
  @Nullable private Long unitCostMinor;

  @Column(name = "variance_value_minor", nullable = false, updatable = false)
  private long varianceValueMinor;

  protected StocktakeLine() {
    // for JPA
  }

  private StocktakeLine(
      UUID stocktakeId,
      UUID menuItemId,
      int systemQty,
      int countedQty,
      int varianceQty,
      @Nullable Long unitCostMinor,
      long varianceValueMinor) {
    if (countedQty < 0) {
      throw new IllegalArgumentException("countedQty must be >= 0");
    }
    this.id = UUID.randomUUID();
    this.stocktakeId = Objects.requireNonNull(stocktakeId, "stocktakeId");
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.systemQty = systemQty;
    this.countedQty = countedQty;
    this.varianceQty = varianceQty;
    this.unitCostMinor = unitCostMinor;
    this.varianceValueMinor = varianceValueMinor;
  }

  /** Records one counted line for a stocktake. */
  public static StocktakeLine of(
      UUID stocktakeId,
      UUID menuItemId,
      int systemQty,
      int countedQty,
      int varianceQty,
      @Nullable Long unitCostMinor,
      long varianceValueMinor) {
    return new StocktakeLine(
        stocktakeId,
        menuItemId,
        systemQty,
        countedQty,
        varianceQty,
        unitCostMinor,
        varianceValueMinor);
  }

  public UUID getId() {
    return id;
  }

  public UUID getStocktakeId() {
    return stocktakeId;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }

  public int getSystemQty() {
    return systemQty;
  }

  public int getCountedQty() {
    return countedQty;
  }

  public int getVarianceQty() {
    return varianceQty;
  }

  @Nullable public Long getUnitCostMinor() {
    return unitCostMinor;
  }

  public long getVarianceValueMinor() {
    return varianceValueMinor;
  }
}
