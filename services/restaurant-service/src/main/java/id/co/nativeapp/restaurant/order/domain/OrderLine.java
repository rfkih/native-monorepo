package id.co.nativeapp.restaurant.order.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A single line within a {@link Order}: the quantity of one {@link
 * id.co.nativeapp.restaurant.menu.domain.MenuItem MenuItem} at its price-at-time-of-order.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code order_line} RLS policy (rule
 * 5). The unit price and line total are integer minor units (rule 8 — never a float); they are
 * individual {@code BIGINT} columns rather than a full {@code MoneyEmbeddable} because each line
 * inherits the order's single currency — there is no per-line currency column (rule 8: single
 * currency per order, enforced by {@code OrderWriter}).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "order_line")
public class OrderLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Column(name = "menu_item_id", nullable = false, updatable = false)
  private UUID menuItemId;

  @Column(name = "name_snapshot", nullable = false, length = 255, updatable = false)
  private String nameSnapshot;

  /** Unit price in minor units (snapshotted from the menu item at time of order). */
  @Column(name = "unit_price_minor", nullable = false, updatable = false)
  private long unitPriceMinor;

  @Column(name = "qty", nullable = false, updatable = false)
  private int qty;

  /** Pre-computed line total = unit_price_minor * qty (integer minor units, never a float). */
  @Column(name = "line_total_minor", nullable = false, updatable = false)
  private long lineTotalMinor;

  protected OrderLine() {
    // for JPA
  }

  /**
   * Creates a line with a freshly generated id, snapshotting the item's name and unit price at
   * order time. The line total is computed here as {@code Math.multiplyExact} (exact, no float).
   *
   * @param menuItemId the originating menu item
   * @param nameSnapshot item name at order time
   * @param unitPrice unit price (Money — validated to match the order currency by the caller)
   * @param qty quantity; must be &ge; 1
   */
  public OrderLine(UUID menuItemId, String nameSnapshot, Money unitPrice, int qty) {
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be >= 1, got: " + qty);
    }
    this.id = UUID.randomUUID();
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    Objects.requireNonNull(unitPrice, "unitPrice");
    this.unitPriceMinor = unitPrice.amountMinor();
    this.qty = qty;
    // Exact integer multiplication — no float path.
    this.lineTotalMinor = Math.multiplyExact(unitPrice.amountMinor(), qty);
  }

  /** Called by {@link Order#addLine} to establish the bidirectional link. */
  void setOrder(Order order) {
    this.order = Objects.requireNonNull(order, "order");
  }

  public UUID getId() {
    return id;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }

  public String getNameSnapshot() {
    return nameSnapshot;
  }

  public long getUnitPriceMinor() {
    return unitPriceMinor;
  }

  public int getQty() {
    return qty;
  }

  public long getLineTotalMinor() {
    return lineTotalMinor;
  }
}
