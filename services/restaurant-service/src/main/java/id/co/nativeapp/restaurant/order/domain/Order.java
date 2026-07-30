package id.co.nativeapp.restaurant.order.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.MoneyEmbeddable;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code restaurant_order} aggregate — a customer order composed of {@link OrderLine}s.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code restaurant_order} RLS policy
 * in {@code V2__pos.sql} (rule 5). The order total is a {@link Money} persisted as {@code
 * total_minor BIGINT + currency CHAR(3)} via {@link MoneyEmbeddable} (rule 8 — never a float).
 *
 * <p>On checkout the owning service calls {@code SaleWriter.create} with the order total so exactly
 * one {@code SaleRecorded} event flows into finance (rule 3). The returned sale id is stored on
 * this aggregate for traceability.
 *
 * <p>Phase 4: an order carries an optional {@code orderType} (DINE_IN / TAKEAWAY / DELIVERY;
 * default DINE_IN) and a nullable {@code tableId} (only for DINE_IN). A PARKED order is a saved
 * cart — no sale, no payment, no revenue — that can be resumed later via {@code /pay}.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "restaurant_order")
public class Order extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "total_minor", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3))
  })
  private MoneyEmbeddable total;

  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  /** Phase 4: DINE_IN / TAKEAWAY / DELIVERY — default DINE_IN. */
  @Column(name = "order_type", nullable = false, length = 16)
  private String orderType;

  /** Phase 4: nullable; only set for DINE_IN orders that reference a specific table. */
  @Column(name = "table_id")
  private UUID tableId;

  /**
   * Phase 4: the order-level fixed discount in minor units, as supplied at park/checkout time.
   * Persisted so {@code payParked} can recompute the same {@link
   * id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown} at pay time without storing the full
   * breakdown (avoids schema duplication; breakdown is always re-derivable from lines + discount +
   * the effective rule at the time of the call). {@code null} means no fixed discount was applied.
   */
  @Column(name = "discount_minor")
  private Long discountMinor;

  /**
   * Phase 3 (ADR 0026): the redeemed {@code coupon} id, if any. Denormalized convenience only — NOT
   * authoritative; the source of truth for what was actually applied (and for how much) is the {@code
   * applied_promotion} audit rows, which is what this order's collapsed {@code discount_minor} was
   * computed from (V16 migration comment). {@code null} means no coupon was redeemed.
   */
  @Column(name = "coupon_id")
  private UUID couponId;

  @OneToMany(
      mappedBy = "order",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<OrderLine> lines = new ArrayList<>();

  protected Order() {
    // for JPA
  }

  /**
   * Creates a new order in PENDING status with a freshly generated id. Lines are added via {@link
   * #addLine}.
   *
   * @param businessId the originating business unit
   * @param total the pre-computed order total as {@link Money} (never a float)
   * @param occurredAt when the order was placed
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public Order(UUID businessId, Money total, Instant occurredAt, String idempotencyKey) {
    this(businessId, total, occurredAt, idempotencyKey, "DINE_IN", null, null);
  }

  /**
   * Creates a new order in PENDING status with explicit order type, optional table, and optional
   * discount.
   *
   * @param businessId the originating business unit
   * @param total the pre-computed order total as {@link Money} (never a float)
   * @param occurredAt when the order was placed
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   * @param orderType DINE_IN / TAKEAWAY / DELIVERY (must not be null)
   * @param tableId nullable; only valid for DINE_IN orders
   * @param discountMinor optional fixed discount in minor units; {@code null} if none
   */
  public Order(
      UUID businessId,
      Money total,
      Instant occurredAt,
      String idempotencyKey,
      String orderType,
      UUID tableId,
      Long discountMinor) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.status = "PENDING";
    this.total = MoneyEmbeddable.of(Objects.requireNonNull(total, "total"));
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.orderType = Objects.requireNonNull(orderType, "orderType");
    this.tableId = tableId;
    this.discountMinor = discountMinor;
  }

  /**
   * Creates a new order in PENDING status with explicit order type and optional table (no
   * discount). Kept for callers that do not supply a discount.
   *
   * @param businessId the originating business unit
   * @param total the pre-computed order total as {@link Money} (never a float)
   * @param occurredAt when the order was placed
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   * @param orderType DINE_IN / TAKEAWAY / DELIVERY (must not be null)
   * @param tableId nullable; only valid for DINE_IN orders
   */
  public Order(
      UUID businessId,
      Money total,
      Instant occurredAt,
      String idempotencyKey,
      String orderType,
      UUID tableId) {
    this(businessId, total, occurredAt, idempotencyKey, orderType, tableId, null);
  }

  /**
   * Adds a line to this order. The line's {@code company_id} is set by the caller (must match the
   * order's company_id so RLS applies).
   */
  public void addLine(OrderLine line) {
    lines.add(line);
    line.setOrder(this);
  }

  /**
   * Links the sale record to this order and transitions to {@code COMPLETED}.
   *
   * <p>Legal source states: {@code PENDING}, {@code PARKED}, {@code AWAITING_PAYMENT}. Calling this
   * on an already-{@code COMPLETED} order throws {@link IllegalStateException} — the sale has
   * already been recorded and a second linkSale call would overwrite {@code sale_id} and lose the
   * audit trail.
   */
  public void linkSale(UUID saleId) {
    if ("COMPLETED".equals(this.status)) {
      throw new IllegalStateException(
          "Order " + id + " is already COMPLETED; linkSale may not be called twice.");
    }
    if (!"PENDING".equals(this.status)
        && !"PARKED".equals(this.status)
        && !"AWAITING_PAYMENT".equals(this.status)) {
      throw new IllegalStateException(
          "linkSale requires PENDING, PARKED, or AWAITING_PAYMENT; current status: " + status);
    }
    this.saleId = Objects.requireNonNull(saleId, "saleId");
    this.status = "COMPLETED";
  }

  /**
   * Transitions this order to {@code AWAITING_PAYMENT} status when a digital tender (QRIS/CARD) is
   * submitted at checkout. The sale is NOT recorded here — it is created only when the digital
   * payment is captured (ADR 0006 revenue-at-capture invariant). The {@code sale_id} remains null
   * until {@link #linkSale} is called at capture time.
   *
   * <p>Legal source state: {@code PENDING} or {@code PARKED}.
   */
  public void markAwaitingPayment() {
    if (!"PENDING".equals(this.status) && !"PARKED".equals(this.status)) {
      throw new IllegalStateException(
          "markAwaitingPayment requires PENDING or PARKED; current status: " + status);
    }
    this.status = "AWAITING_PAYMENT";
  }

  /**
   * Phase 4: Transitions this order to {@code PARKED} status. A parked order is a saved cart — it
   * carries NO sale, NO payment, and NO revenue. Revenue is recognised only when {@code /pay} is
   * called and the order is finalised. The {@code sale_id} remains null until {@link #linkSale} is
   * called at pay-time.
   *
   * <p>This invariant is equivalent to the {@code AWAITING_PAYMENT} digital-capture invariant: no
   * SaleRecorded outbox row is written when parking an order.
   *
   * <p>Legal source state: {@code PENDING}.
   */
  public void markParked() {
    if (!"PENDING".equals(this.status)) {
      throw new IllegalStateException("markParked requires PENDING; current status: " + status);
    }
    this.status = "PARKED";
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getStatus() {
    return status;
  }

  /** The order total as a {@link Money} value (reconstructed from its columns). */
  public Money getTotal() {
    return total.toMoney();
  }

  /**
   * Phase 3 (ADR 0026): re-prices this order's total to the freshly re-evaluated {@link
   * id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown} grand total at {@code payParked} time.
   * A parked order's total is only a park-time SNAPSHOT — promotions (automatic rules, a coupon)
   * evaluate at pay time, not park time, so the true charged amount can differ from what was stored
   * when the cart was parked. Must be called BEFORE recording the sale/payment so every downstream
   * consumer of {@link #getTotal()} (the sale amount, the payment instruction, the response) agrees
   * on the one true final total. Currency never changes (same order, same cart).
   */
  public void updateTotal(Money newTotal) {
    Objects.requireNonNull(newTotal, "newTotal");
    this.total = MoneyEmbeddable.of(newTotal);
  }

  public UUID getSaleId() {
    return saleId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public List<OrderLine> getLines() {
    return Collections.unmodifiableList(lines);
  }

  /** Phase 4: the order type (DINE_IN / TAKEAWAY / DELIVERY). */
  public String getOrderType() {
    return orderType;
  }

  /** Phase 4: nullable; the table this order is associated with (DINE_IN only). */
  public UUID getTableId() {
    return tableId;
  }

  /**
   * Phase 4: the fixed discount applied at park/checkout time, in currency minor units. {@code
   * null} means no fixed discount. Stored so {@code payParked} can recompute the same breakdown at
   * pay time without persisting the entire breakdown.
   */
  public Long getDiscountMinor() {
    return discountMinor;
  }

  /** Phase 3 (ADR 0026): stamps the redeemed coupon id. Called once, at the moment of redemption. */
  public void attachCoupon(UUID couponId) {
    this.couponId = Objects.requireNonNull(couponId, "couponId");
  }

  /** Phase 3 (ADR 0026): the redeemed coupon id, or {@code null} if none was used. */
  public UUID getCouponId() {
    return couponId;
  }
}
