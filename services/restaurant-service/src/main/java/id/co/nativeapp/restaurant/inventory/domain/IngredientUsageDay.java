package id.co.nativeapp.restaurant.inventory.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ingredient's total quantity CONSUMED by sales on one outlet-local calendar day ("stock
 * terpakai") — a derived operational aggregate written by {@code IngredientDepletionWriter}'s
 * per-sale UPSERT in the same transaction as the sale (V42). Read by the stock-opname sheet
 * ("terpakai hari ini") and the opname history detail.
 *
 * <p>The entity exists to anchor the repository and mirror the schema; ALL writes go through the
 * repository's native {@code INSERT … ON CONFLICT} (a JPA save could not express the atomic
 * increment), so the {@code @Version} column is a plain counter here, not an optimistic lock in
 * use. Quantities are integer counts in the ingredient's own unit (never money). Extends {@link
 * Auditable} (rules 4 + 5, V42 RLS policy).
 */
@Entity
@Table(name = "ingredient_usage_day")
public class IngredientUsageDay extends Auditable {

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  @Column(name = "usage_date", nullable = false, updatable = false)
  private LocalDate usageDate;

  @Column(name = "qty_used", nullable = false)
  private long qtyUsed;

  protected IngredientUsageDay() {
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

  public LocalDate getUsageDate() {
    return usageDate;
  }

  public long getQtyUsed() {
    return qtyUsed;
  }
}
