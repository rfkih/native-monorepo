package id.co.nativeapp.restaurant.inventory.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One counted ingredient within an {@link IngredientStocktake} (ADR 0046 phase 1) — the audit trail
 * behind the parent's aggregate {@code shrinkageMinor}. A clone of {@code
 * id.co.nativeapp.restaurant.stocktake.domain.StocktakeLine} keyed by {@link Ingredient} rather
 * than a menu item. {@code varianceQty = countedQty − systemQty} (positive = found more, negative =
 * shrinkage); {@code varianceValueMinor = varianceQty × unitCostMinor}, or {@code 0} when the
 * ingredient carries no cost (no ledger impact).
 *
 * <p>Extends {@link Auditable} (rules 4 + 5), covered by the V31 RLS policy.
 */
@Entity
@Table(name = "ingredient_stocktake_line")
public class IngredientStocktakeLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ingredient_stocktake_id", nullable = false, updatable = false)
  private UUID ingredientStocktakeId;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  @Column(name = "system_qty", nullable = false, updatable = false)
  private int systemQty;

  @Column(name = "counted_qty", nullable = false, updatable = false)
  private int countedQty;

  @Column(name = "variance_qty", nullable = false, updatable = false)
  private int varianceQty;

  /** The ingredient's unit cost at count time; {@code null} when it had no cost (no GL impact). */
  @Column(name = "unit_cost_minor")
  @Nullable private Long unitCostMinor;

  @Column(name = "variance_value_minor", nullable = false, updatable = false)
  private long varianceValueMinor;

  protected IngredientStocktakeLine() {
    // for JPA
  }

  private IngredientStocktakeLine(
      UUID ingredientStocktakeId,
      UUID ingredientId,
      int systemQty,
      int countedQty,
      int varianceQty,
      @Nullable Long unitCostMinor,
      long varianceValueMinor) {
    if (countedQty < 0) {
      throw new IllegalArgumentException("countedQty must be >= 0");
    }
    this.id = UUID.randomUUID();
    this.ingredientStocktakeId =
        Objects.requireNonNull(ingredientStocktakeId, "ingredientStocktakeId");
    this.ingredientId = Objects.requireNonNull(ingredientId, "ingredientId");
    this.systemQty = systemQty;
    this.countedQty = countedQty;
    this.varianceQty = varianceQty;
    this.unitCostMinor = unitCostMinor;
    this.varianceValueMinor = varianceValueMinor;
  }

  /** Records one counted line for an ingredient stocktake. */
  public static IngredientStocktakeLine of(
      UUID ingredientStocktakeId,
      UUID ingredientId,
      int systemQty,
      int countedQty,
      int varianceQty,
      @Nullable Long unitCostMinor,
      long varianceValueMinor) {
    return new IngredientStocktakeLine(
        ingredientStocktakeId,
        ingredientId,
        systemQty,
        countedQty,
        varianceQty,
        unitCostMinor,
        varianceValueMinor);
  }

  public UUID getId() {
    return id;
  }

  public UUID getIngredientStocktakeId() {
    return ingredientStocktakeId;
  }

  public UUID getIngredientId() {
    return ingredientId;
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
