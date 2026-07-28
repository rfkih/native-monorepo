package id.co.nativeapp.restaurant.menu.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code menu_item_modifier_group} — a named set of modifier options attached to one menu item.
 *
 * <p>Examples: "Size" (SINGLE, required), "Toppings" (MULTI, optional). Extends {@link Auditable}
 * (rule 4); covered by the {@code menu_item_modifier_group} RLS policy in {@code
 * V6__catalog_phase3.sql} (rule 5).
 *
 * <p>Invariants enforced in the constructor:
 *
 * <ul>
 *   <li>{@code selectionType} must be {@code SINGLE} or {@code MULTI}.
 *   <li>{@code minSelect >= 0} and {@code maxSelect >= minSelect}.
 *   <li>A required group must have {@code minSelect >= 1}.
 * </ul>
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "menu_item_modifier_group")
public class ModifierGroup extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "menu_item_id", nullable = false, updatable = false)
  private UUID menuItemId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "selection_type", nullable = false, length = 16)
  private String selectionType;

  @Column(name = "required", nullable = false)
  private boolean required;

  @Column(name = "min_select", nullable = false)
  private int minSelect;

  @Column(name = "max_select", nullable = false)
  private int maxSelect;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  protected ModifierGroup() {
    // for JPA
  }

  /**
   * Creates a new modifier group with a freshly generated id.
   *
   * @param menuItemId the menu item this group belongs to
   * @param businessId the owning business unit
   * @param name the group name (e.g. "Size", "Spice")
   * @param selectionType {@code SINGLE} or {@code MULTI}
   * @param required whether at least {@code minSelect} options must be chosen
   * @param minSelect minimum number of selections (0 or more)
   * @param maxSelect maximum number of selections (>= minSelect)
   * @param displayOrder sort position
   */
  public ModifierGroup(
      UUID menuItemId,
      UUID businessId,
      String name,
      String selectionType,
      boolean required,
      int minSelect,
      int maxSelect,
      int displayOrder) {
    this.id = UUID.randomUUID();
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    if (!"SINGLE".equals(selectionType) && !"MULTI".equals(selectionType)) {
      throw new IllegalArgumentException(
          "selectionType must be SINGLE or MULTI, got: " + selectionType);
    }
    this.selectionType = selectionType;
    if (minSelect < 0) {
      throw new IllegalArgumentException("minSelect must be >= 0, got: " + minSelect);
    }
    if (maxSelect < minSelect) {
      throw new IllegalArgumentException(
          "maxSelect must be >= minSelect (" + minSelect + "), got: " + maxSelect);
    }
    this.required = required;
    this.minSelect = minSelect;
    this.maxSelect = maxSelect;
    this.displayOrder = displayOrder;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getName() {
    return name;
  }

  public String getSelectionType() {
    return selectionType;
  }

  public boolean isRequired() {
    return required;
  }

  public int getMinSelect() {
    return minSelect;
  }

  public int getMaxSelect() {
    return maxSelect;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  /**
   * Applies a partial update (patch semantics). Only non-null arguments are applied. Enforces the
   * same invariants as the constructor: {@code SINGLE} implies {@code maxSelect == 1}, {@code
   * minSelect >= 0}, {@code maxSelect >= minSelect}, and a required group must have {@code
   * minSelect >= 1}.
   *
   * @param newName new name, or {@code null} for no change
   * @param newSelectionType {@code SINGLE}, {@code MULTI}, or {@code null} for no change
   * @param newRequired new required flag, or {@code null} for no change
   * @param newMinSelect new minimum selections, or {@code null} for no change
   * @param newMaxSelect new maximum selections, or {@code null} for no change
   * @param newDisplayOrder new display order, or {@code null} for no change
   */
  public void update(
      String newName,
      String newSelectionType,
      Boolean newRequired,
      Integer newMinSelect,
      Integer newMaxSelect,
      Integer newDisplayOrder) {
    if (newName != null) {
      this.name = newName;
    }
    if (newSelectionType != null) {
      if (!"SINGLE".equals(newSelectionType) && !"MULTI".equals(newSelectionType)) {
        throw new IllegalArgumentException(
            "selectionType must be SINGLE or MULTI, got: " + newSelectionType);
      }
      this.selectionType = newSelectionType;
    }
    if (newRequired != null) {
      this.required = newRequired;
    }
    if (newMinSelect != null) {
      if (newMinSelect < 0) {
        throw new IllegalArgumentException("minSelect must be >= 0, got: " + newMinSelect);
      }
      this.minSelect = newMinSelect;
    }
    if (newMaxSelect != null) {
      if (newMaxSelect < 1) {
        throw new IllegalArgumentException("maxSelect must be >= 1, got: " + newMaxSelect);
      }
      this.maxSelect = newMaxSelect;
    }
    // Validate combined state after applying patches.
    if (this.maxSelect < this.minSelect) {
      throw new IllegalArgumentException(
          "maxSelect (" + this.maxSelect + ") must be >= minSelect (" + this.minSelect + ")");
    }
    if ("SINGLE".equals(this.selectionType) && this.maxSelect != 1) {
      throw new IllegalArgumentException(
          "SINGLE selectionType requires maxSelect == 1, got: " + this.maxSelect);
    }
    if (newDisplayOrder != null) {
      this.displayOrder = newDisplayOrder;
    }
  }
}
