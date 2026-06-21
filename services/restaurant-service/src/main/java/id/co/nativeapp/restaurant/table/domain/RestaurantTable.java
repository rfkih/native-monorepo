package id.co.nativeapp.restaurant.table.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code restaurant_table} aggregate — a physical seating table within a business.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code restaurant_table} RLS policy
 * in {@code V7__phase4_tables_and_order_ops.sql} (rule 5).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "restaurant_table")
public class RestaurantTable extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "label", nullable = false, length = 64)
  private String label;

  @Column(name = "capacity", nullable = false)
  private int capacity;

  @Column(name = "area", length = 128)
  private String area;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected RestaurantTable() {
    // for JPA
  }

  /**
   * Creates a new, active restaurant table.
   *
   * @param businessId the owning business unit
   * @param label a short human-readable label, e.g. "T1" (unique per company+business)
   * @param capacity number of seats
   * @param area optional area/zone descriptor, e.g. "Indoor"
   */
  public RestaurantTable(UUID businessId, String label, int capacity, String area) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.label = Objects.requireNonNull(label, "label");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1, got: " + capacity);
    }
    this.capacity = capacity;
    this.area = area;
    this.active = true;
  }

  /** Activates this table (makes it selectable for new orders). */
  public void activate() {
    this.active = true;
  }

  /** Deactivates this table (prevents it from being assigned to new orders). */
  public void deactivate() {
    this.active = false;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getLabel() {
    return label;
  }

  public int getCapacity() {
    return capacity;
  }

  public String getArea() {
    return area;
  }

  public boolean isActive() {
    return active;
  }
}
