package id.co.nativeapp.barbershop.catalog.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code service_addon} aggregate — one priced upsell (e.g. hot towel) layered on top of a
 * {@link ServiceItem} (V1 baseline). Renamed from carwash-service's {@code WashAddon} (ADR 0024).
 * Identical shape to {@link ServiceItem} MINUS {@code durationMinutes} (an addon has no
 * appointment-slot duration of its own) — the migration author kept the two tables otherwise
 * disjoint on purpose, never joined. Extends {@link Auditable} (rule 4) and is covered by the
 * {@code service_addon} RLS policy (rule 5).
 *
 * <p>The price is a {@code libs/money} {@link Money} (rule 8), persisted via the reused {@link
 * MoneyEmbeddable}. {@code businessId} is the barbershop outlet (org_unit) this addon is offered
 * at.
 */
@Entity
@Table(name = "service_addon")
public class ServiceAddon extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  // MoneyEmbeddable defaults to amount_minor; the catalog tables' price column is price_minor.
  @Embedded
  @AttributeOverride(name = "amountMinor", column = @Column(name = "price_minor", nullable = false))
  private MoneyEmbeddable price;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  protected ServiceAddon() {
    // for JPA
  }

  /** Creates a new, active service addon with a freshly generated id. */
  public ServiceAddon(
      UUID businessId, String name, String description, Money price, int displayOrder) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = requireNonBlank(name, "name");
    this.description = description;
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(price, "price"));
    this.active = true;
    this.displayOrder = displayOrder;
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

  public String getDescription() {
    return description;
  }

  /** The addon price as a {@link Money} value (reconstructed from its columns). */
  public Money getPrice() {
    return price.toMoney();
  }

  public boolean isActive() {
    return active;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void rename(String name) {
    this.name = requireNonBlank(name, "name");
  }

  public void updateDescription(String description) {
    this.description = description;
  }

  public void reprice(Money price) {
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(price, "price"));
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
