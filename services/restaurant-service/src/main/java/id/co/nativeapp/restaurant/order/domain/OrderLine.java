package id.co.nativeapp.restaurant.order.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <p>Phase 3: the line also carries an optional list of {@link OrderLineModifier} snapshots for
 * selected modifier options. The effective unit price is:
 *
 * <pre>
 *   effectiveUnitPrice = unitPriceMinor + Σ priceDeltaMinor (over selected modifiers)
 * </pre>
 *
 * The {@code lineTotalMinor = effectiveUnitPriceMinor × qty} (exact integer math, no float).
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

  /** Base unit price in minor units (snapshotted from the menu item at time of order). */
  @Column(name = "unit_price_minor", nullable = false, updatable = false)
  private long unitPriceMinor;

  @Column(name = "qty", nullable = false, updatable = false)
  private int qty;

  /**
   * Pre-computed line total = effectiveUnitPriceMinor × qty (integer minor units, never a float).
   * The effective unit price is the base unit price plus the sum of all modifier price deltas.
   */
  @Column(name = "line_total_minor", nullable = false, updatable = false)
  private long lineTotalMinor;

  @OneToMany(
      mappedBy = "orderLine",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<OrderLineModifier> modifiers = new ArrayList<>();

  protected OrderLine() {
    // for JPA
  }

  /**
   * Creates a line with a freshly generated id, snapshotting the item's name and effective unit
   * price at order time. The effective unit price is {@code unitPrice.amountMinor() +
   * modifierDeltaMinor}. The line total is computed as {@code Math.multiplyExact} (exact, no
   * float). A negative effective unit price is rejected.
   *
   * @param menuItemId the originating menu item
   * @param nameSnapshot item name at order time
   * @param unitPrice base unit price (Money — validated to match the order currency by the caller)
   * @param modifierDeltaMinor sum of selected modifier price deltas (signed; may be 0)
   * @param qty quantity; must be &ge; 1
   */
  public OrderLine(
      UUID menuItemId, String nameSnapshot, Money unitPrice, long modifierDeltaMinor, int qty) {
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be >= 1, got: " + qty);
    }
    this.id = UUID.randomUUID();
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    Objects.requireNonNull(unitPrice, "unitPrice");
    this.unitPriceMinor = unitPrice.amountMinor();
    this.qty = qty;
    // Effective unit price = base price + modifier delta (exact integer math, no float).
    long effectiveUnitPrice = Math.addExact(unitPrice.amountMinor(), modifierDeltaMinor);
    if (effectiveUnitPrice < 0) {
      throw new IllegalArgumentException(
          "Effective unit price must be >= 0 after modifiers, got: " + effectiveUnitPrice);
    }
    this.lineTotalMinor = Math.multiplyExact(effectiveUnitPrice, qty);
  }

  /**
   * Convenience constructor with zero modifier delta — preserves the existing two-arg call sites
   * (name + price + qty) without requiring callers to pass 0L for modifierDeltaMinor.
   */
  public OrderLine(UUID menuItemId, String nameSnapshot, Money unitPrice, int qty) {
    this(menuItemId, nameSnapshot, unitPrice, 0L, qty);
  }

  /** Called by {@link Order#addLine} to establish the bidirectional link. */
  void setOrder(Order order) {
    this.order = Objects.requireNonNull(order, "order");
  }

  /**
   * Appends a modifier snapshot to this line. Called by {@code OrderWriter} after construction to
   * attach selected options, before the line is flushed to the DB.
   */
  public void addModifier(OrderLineModifier modifier) {
    modifiers.add(modifier);
    modifier.setOrderLine(this);
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

  public List<OrderLineModifier> getModifiers() {
    return Collections.unmodifiableList(modifiers);
  }
}
