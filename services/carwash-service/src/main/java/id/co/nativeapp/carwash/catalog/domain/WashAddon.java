package id.co.nativeapp.carwash.catalog.domain;

import id.co.nativeapp.carwash.wash.domain.MoneyEmbeddable;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code wash_addon} aggregate — one priced upsell (wax, interior, engine wash) layered on top
 * of a {@link WashPackage} (V4). Identical shape to {@link WashPackage} (the migration author kept
 * the two tables column-for-column disjoint on purpose — packages and addons are separate catalogs,
 * never joined). Extends {@link Auditable} (rule 4) and is covered by the {@code wash_addon} RLS
 * policy (rule 5).
 *
 * <p>The price is a {@code libs/money} {@link Money} (rule 8), persisted via the reused {@link
 * MoneyEmbeddable}. {@code businessId} is the carwash outlet (org_unit) this addon is offered at.
 */
@Entity
@Table(name = "wash_addon")
public class WashAddon extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Embedded private MoneyEmbeddable price;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  protected WashAddon() {
    // for JPA
  }

  /** Creates a new, active wash addon with a freshly generated id. */
  public WashAddon(
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
