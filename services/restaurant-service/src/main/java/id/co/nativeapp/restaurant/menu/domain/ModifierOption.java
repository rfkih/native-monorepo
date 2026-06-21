package id.co.nativeapp.restaurant.menu.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code menu_item_modifier_option} — one choice within a {@link ModifierGroup}.
 *
 * <p>Examples: "Large" (+5 000 IDR), "Extra Spicy" (+0), "No Onion" (−0). The {@code
 * priceDeltaMinor} is signed: positive = surcharge, zero = no change, negative = discount-variant.
 * It is stored as a {@code BIGINT} (never a float — rule 8).
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code menu_item_modifier_option} RLS
 * policy in {@code V6__catalog_phase3.sql} (rule 5).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "menu_item_modifier_option")
public class ModifierOption extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /**
   * Signed price delta in minor units (rule 8 — never a float). Positive = surcharge (e.g. Large +5
   * 000 IDR), zero = no delta, negative = discount-variant.
   */
  @Column(name = "price_delta_minor", nullable = false)
  private long priceDeltaMinor;

  @Column(name = "available", nullable = false)
  private boolean available = true;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  protected ModifierOption() {
    // for JPA
  }

  /**
   * Creates a new modifier option with a freshly generated id.
   *
   * @param groupId the modifier group this option belongs to
   * @param businessId the owning business unit
   * @param name the option name (e.g. "Large", "Extra Spicy")
   * @param priceDeltaMinor signed price delta in minor units (never a float)
   * @param displayOrder sort position
   */
  public ModifierOption(
      UUID groupId, UUID businessId, String name, long priceDeltaMinor, int displayOrder) {
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    this.priceDeltaMinor = priceDeltaMinor;
    this.displayOrder = displayOrder;
    this.available = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getName() {
    return name;
  }

  public long getPriceDeltaMinor() {
    return priceDeltaMinor;
  }

  public boolean isAvailable() {
    return available;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  /** Marks this option as unavailable (86'd). */
  public void markUnavailable() {
    this.available = false;
  }

  /** Marks this option as available again. */
  public void markAvailable() {
    this.available = true;
  }
}
