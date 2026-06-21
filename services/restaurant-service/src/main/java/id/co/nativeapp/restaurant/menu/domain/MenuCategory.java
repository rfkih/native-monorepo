package id.co.nativeapp.restaurant.menu.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code menu_category} aggregate — a display-ordered grouping for menu items.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code menu_category} RLS policy in
 * {@code V6__catalog_phase3.sql} (rule 5).
 *
 * <p>{@code active = false} hides the entire category (and its items) from the cashier view. This
 * is DISTINCT from {@code available} on {@link MenuItem}: active governs the catalog, while
 * available governs real-time sellability.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "menu_category")
public class MenuCategory extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  protected MenuCategory() {
    // for JPA
  }

  /**
   * Creates a new active category with a freshly generated id.
   *
   * @param businessId the owning business unit
   * @param name display name (non-blank)
   * @param displayOrder sort position (ascending; ties resolved by name)
   */
  public MenuCategory(UUID businessId, String name, int displayOrder) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    this.displayOrder = displayOrder;
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getName() {
    return name;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public boolean isActive() {
    return active;
  }

  /** Updates the display order for reordering. */
  public void reorder(int newDisplayOrder) {
    this.displayOrder = newDisplayOrder;
  }

  /** Activates this category — all its items become visible in the cashier view. */
  public void activate() {
    this.active = true;
  }

  /** Deactivates this category — all its items are hidden from the cashier view. */
  public void deactivate() {
    this.active = false;
  }

  /** Updates the category name. */
  public void rename(String newName) {
    this.name = Objects.requireNonNull(newName, "name");
  }
}
