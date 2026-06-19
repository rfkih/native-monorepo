package id.co.nativeapp.restaurant.menu.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.MoneyEmbeddable;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code menu_item} aggregate — a priced offering on a business's menu.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code menu_item} RLS policy in
 * {@code V2__pos.sql} (rule 5). The price is a {@link Money} persisted as {@code price_minor BIGINT
 * + currency CHAR(3)} via {@link MoneyEmbeddable} (rule 8 — never a float).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "menu_item")
public class MenuItem extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "category", nullable = false, length = 64)
  private String category;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "price_minor", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3))
  })
  private MoneyEmbeddable price;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  protected MenuItem() {
    // for JPA
  }

  /**
   * Creates a new active menu item with a freshly generated id.
   *
   * @param businessId the owning business unit
   * @param name display name (non-blank)
   * @param category item category (non-blank)
   * @param price the price as {@link Money} (never a float, rule 8)
   */
  public MenuItem(UUID businessId, String name, String category, Money price) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    this.category = Objects.requireNonNull(category, "category");
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(price, "price"));
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

  public String getCategory() {
    return category;
  }

  /** The item price as a {@link Money} value (reconstructed from its columns). */
  public Money getPrice() {
    return price.toMoney();
  }

  public boolean isActive() {
    return active;
  }

  /** Deactivates this item — it will no longer appear in active-item reads. */
  public void deactivate() {
    this.active = false;
  }
}
