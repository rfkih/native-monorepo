package id.co.nativeapp.restaurant.bill.domain;

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
 * A single line within a {@link Bill} — the splittable unit of the tab.
 *
 * <p>Designed to be individually addressable so Increment 3 can assign lines to separate checks.
 * Extends {@link Auditable} (rule 4); covered by the {@code bill_line_tenant_isolation} RLS policy
 * (rule 5). All monetary amounts are BIGINT minor units — never a float (rule 8).
 *
 * <p>The effective unit price is: {@code unitPriceMinor + modifierDeltaMinor}. The line total is:
 * {@code effectiveUnitPrice × qty} (exact integer math).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "bill_line")
public class BillLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "bill_id", nullable = false, updatable = false)
  private Bill bill;

  @Column(name = "menu_item_id", nullable = false, updatable = false)
  private UUID menuItemId;

  @Column(name = "name_snapshot", nullable = false, length = 255, updatable = false)
  private String nameSnapshot;

  /** Base unit price in minor units (snapshotted from the menu item at append time). */
  @Column(name = "unit_price_minor", nullable = false, updatable = false)
  private long unitPriceMinor;

  /** Sum of selected modifier price deltas in minor units (signed; 0 when no modifiers). */
  @Column(name = "modifier_delta_minor", nullable = false, updatable = false)
  private long modifierDeltaMinor;

  @Column(name = "qty", nullable = false, updatable = false)
  private int qty;

  /**
   * Pre-computed line total = (unitPriceMinor + modifierDeltaMinor) × qty. Integer exact math,
   * never a float.
   */
  @Column(name = "line_total_minor", nullable = false, updatable = false)
  private long lineTotalMinor;

  /** {@code true} once this line has been included in a paid check. */
  @Column(name = "paid", nullable = false)
  private boolean paid = false;

  /**
   * The sale id of the check that paid this line. Null until this line is paid. Not a FK — the sale
   * belongs to the sale aggregate; we store the id snapshot for audit purposes.
   */
  @Column(name = "paid_sale_id")
  private UUID paidSaleId;

  /**
   * The in-flight gateway payment this line is currently RESERVED against (V38), or {@code null}.
   * Written ONLY via {@code BillLineRepository}'s native {@code @Modifying} reserve/release/mark-
   * paid queries — never through an entity setter/{@code save()} — but mapped here READ-ONLY
   * ({@code insertable = false, updatable = false}) so {@link Bill#removeLine} can enforce the "not
   * reserved" invariant against a freshly-loaded aggregate (hardening — code review: removing a
   * reserved line out from under an in-flight payment would strand real PSP money at capture time).
   */
  @Column(name = "pending_payment_id", insertable = false, updatable = false)
  private UUID pendingPaymentId;

  @OneToMany(
      mappedBy = "billLine",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<BillLineModifier> modifiers = new ArrayList<>();

  protected BillLine() {
    // for JPA
  }

  /**
   * Creates a line with a freshly generated id, snapshotting the item's name and effective unit
   * price at append time. The effective unit price is {@code unitPrice.amountMinor() +
   * modifierDeltaMinor}.
   *
   * @param menuItemId the originating menu item
   * @param nameSnapshot item name at append time
   * @param unitPrice base unit price (Money — validated to match the bill currency by the caller)
   * @param modifierDeltaMinor sum of selected modifier price deltas (signed; may be 0)
   * @param qty quantity; must be &ge; 1
   */
  public BillLine(
      UUID menuItemId, String nameSnapshot, Money unitPrice, long modifierDeltaMinor, int qty) {
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be >= 1, got: " + qty);
    }
    this.id = UUID.randomUUID();
    this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
    this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    Objects.requireNonNull(unitPrice, "unitPrice");
    this.unitPriceMinor = unitPrice.amountMinor();
    this.modifierDeltaMinor = modifierDeltaMinor;
    this.qty = qty;
    long effectiveUnitPrice = Math.addExact(unitPrice.amountMinor(), modifierDeltaMinor);
    if (effectiveUnitPrice < 0) {
      throw new IllegalArgumentException(
          "Effective unit price must be >= 0 after modifiers, got: " + effectiveUnitPrice);
    }
    this.lineTotalMinor = Math.multiplyExact(effectiveUnitPrice, qty);
  }

  /** Called by {@link Bill#addLine} to establish the bidirectional link. */
  void setBill(Bill bill) {
    this.bill = Objects.requireNonNull(bill, "bill");
  }

  /** Appends a modifier snapshot to this line. */
  public void addModifier(BillLineModifier modifier) {
    modifiers.add(modifier);
    modifier.setBillLine(this);
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

  public long getModifierDeltaMinor() {
    return modifierDeltaMinor;
  }

  public int getQty() {
    return qty;
  }

  public long getLineTotalMinor() {
    return lineTotalMinor;
  }

  public List<BillLineModifier> getModifiers() {
    return Collections.unmodifiableList(modifiers);
  }

  /**
   * Marks this line as paid by the given check sale.
   *
   * @param saleId the id of the sale that paid this line
   * @throws IllegalStateException if the line is already paid
   */
  public void markPaid(UUID saleId) {
    if (this.paid) {
      throw new IllegalStateException(
          "BillLine " + id + " is already paid (paidSaleId=" + paidSaleId + ")");
    }
    this.paid = true;
    this.paidSaleId = Objects.requireNonNull(saleId, "saleId");
  }

  public boolean isPaid() {
    return paid;
  }

  public UUID getPaidSaleId() {
    return paidSaleId;
  }

  /**
   * {@code null} unless this line is currently RESERVED for an in-flight gateway payment (V38) —
   * see the field javadoc.
   */
  public UUID getPendingPaymentId() {
    return pendingPaymentId;
  }
}
