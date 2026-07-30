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
 * The {@code service_item} aggregate — one priced barbershop offering (e.g. "Haircut", "Shave",
 * "Coloring") a ticket can be built from (V1 baseline). Renamed from carwash-service's {@code
 * WashPackage} (ADR 0024). Extends {@link Auditable} (rule 4) and is covered by the {@code
 * service_item} RLS policy (rule 5).
 *
 * <p>The price is a {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217,
 * never a float), persisted via the reused {@link MoneyEmbeddable} (the same {@code price_minor}/
 * {@code currency} column shape as {@code service_addon}/{@code barbershop_payment}). {@code
 * businessId} is the barbershop outlet (org_unit) this service is offered at.
 *
 * <p>{@code durationMinutes} is RESERVED for a future appointments app: persisted and exposed
 * read-only in responses, settable on create/patch, but otherwise UNUSED by this POS-checkout slice
 * (pricing and ticketing never read it) — a domain difference from carwash-service's {@code
 * WashPackage}, which has no such column.
 *
 * <p>Writes go through {@link id.co.nativeapp.barbershop.catalog.service.CatalogWriter
 * CatalogWriter} (write path: the full entity, via inherited {@code findById}/{@code save}); reads
 * go through the native-query {@link id.co.nativeapp.barbershop.catalog.projection.CatalogItemView
 * CatalogItemView} projection.
 */
@Entity
@Table(name = "service_item")
public class ServiceItem extends Auditable {

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

  /** RESERVED for a future appointments app; unused by pricing/ticketing (see class javadoc). */
  @Column(name = "duration_minutes")
  private Integer durationMinutes;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  protected ServiceItem() {
    // for JPA
  }

  /** Creates a new, active service item with a freshly generated id. */
  public ServiceItem(
      UUID businessId,
      String name,
      String description,
      Money price,
      Integer durationMinutes,
      int displayOrder) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = requireNonBlank(name, "name");
    this.description = description;
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(price, "price"));
    this.durationMinutes = requirePositiveOrNull(durationMinutes);
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

  /** The service price as a {@link Money} value (reconstructed from its columns). */
  public Money getPrice() {
    return price.toMoney();
  }

  public Integer getDurationMinutes() {
    return durationMinutes;
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

  public void updateDurationMinutes(Integer durationMinutes) {
    this.durationMinutes = requirePositiveOrNull(durationMinutes);
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }

  private static Integer requirePositiveOrNull(Integer durationMinutes) {
    if (durationMinutes != null && durationMinutes <= 0) {
      throw new IllegalArgumentException("durationMinutes must be positive: " + durationMinutes);
    }
    return durationMinutes;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
