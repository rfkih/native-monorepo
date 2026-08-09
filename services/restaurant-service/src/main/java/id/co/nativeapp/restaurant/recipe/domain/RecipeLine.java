package id.co.nativeapp.restaurant.recipe.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One line of a menu item's recipe/BOM (ADR 0050 phase A): the integer quantity of one {@link
 * id.co.nativeapp.restaurant.inventory.domain.Ingredient} consumed per portion sold.
 *
 * <p>Two row shapes share this table, discriminated by {@link #modifierOptionId}:
 *
 * <ul>
 *   <li>{@code modifierOptionId == null} — a BASE line: {@code qtyPerPortion > 0} units of the
 *       ingredient are consumed by every portion of the menu item.
 *   <li>{@code modifierOptionId != null} — a signed PER-OPTION delta: applied on top of the base
 *       lines only when that modifier option is selected on the sold line (e.g. "extra cheese" +20
 *       g, "no cheese" −20 g). Nonzero, may be negative; the depletion writer clamps each
 *       ingredient's net per-portion usage at zero so a "remove" delta can never restock.
 * </ul>
 *
 * <p>Quantities are integers in the ingredient's OWN unit (g / ml / pcs / pack) — the ArchUnit
 * {@code entitiesHaveNoDecimalMoneyFields} rule bans decimal fields on entities, and IDR's zero
 * minor exponent makes per-gram integer costing honest (see ADR 0050 rounding note).
 *
 * <p>No JPA relations and no DB FKs to {@code menu_item} / {@code ingredient} / {@code
 * menu_item_modifier_option} — the {@code ingredient_stocktake_line.ingredient_id} precedent;
 * referential integrity is enforced by {@code RecipeWriter} at write time, and modifier
 * hard-deletes cascade via {@code RecipeLineRepository#deleteByModifierOptionIds}. Extends {@link
 * Auditable} (rule 4); covered by the V34 RLS policy (rule 5).
 */
@Entity
@Table(name = "recipe_line")
public class RecipeLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "menu_item_id", nullable = false, updatable = false)
  private UUID menuItemId;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  /** {@code null} = base line; non-null = signed delta scoped to that modifier option. */
  @Column(name = "modifier_option_id", updatable = false)
  @Nullable private UUID modifierOptionId;

  /** Base line: strictly positive. Option delta: nonzero, signed. Enforced by the V34 CHECK too. */
  @Column(name = "qty_per_portion", nullable = false)
  private int qtyPerPortion;

  protected RecipeLine() {
    // for JPA
  }

  /**
   * Creates a recipe line with a freshly generated id.
   *
   * @throws RecipeValidationException if the qty violates the base/option sign rules
   */
  public RecipeLine(
      UUID businessId,
      UUID menuItemId,
      UUID ingredientId,
      @Nullable UUID modifierOptionId,
      int qtyPerPortion) {
    if (modifierOptionId == null && qtyPerPortion <= 0) {
      throw new RecipeValidationException("A base recipe line requires qtyPerPortion > 0");
    }
    if (modifierOptionId != null && qtyPerPortion == 0) {
      throw new RecipeValidationException("A modifier-option delta requires a nonzero qty");
    }
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.ingredientId = Objects.requireNonNull(ingredientId, "ingredientId");
    this.modifierOptionId = modifierOptionId;
    this.qtyPerPortion = qtyPerPortion;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }

  public UUID getIngredientId() {
    return ingredientId;
  }

  @Nullable public UUID getModifierOptionId() {
    return modifierOptionId;
  }

  public int getQtyPerPortion() {
    return qtyPerPortion;
  }
}
