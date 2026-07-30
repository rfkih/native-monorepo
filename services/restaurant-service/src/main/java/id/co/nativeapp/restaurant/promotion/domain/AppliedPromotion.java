package id.co.nativeapp.restaurant.promotion.domain;

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
 * The per-sale AUDIT TRAIL row for one rule's deduction (ADR 0026) — a permanent SNAPSHOT that must
 * keep meaning exactly what it meant at application time even if the {@link PromoRule}/{@link
 * Coupon} row is later edited or retired. Maps to the {@code applied_promotion} table
 * (V16__promotions.sql).
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code applied_promotion_tenant_isolation}
 * RLS policy (rule 5). {@code amount_minor}/{@code currency} use {@link MoneyEmbeddable} (rule 8)
 * since — unlike {@link PromoRule}'s conditional amount — an applied-promotion row always carries
 * the actual minor-unit amount it discounted.
 *
 * <p><strong>{@code orderId} naming.</strong> The migration's {@code order_id} column is a
 * per-vertical generic "source aggregate id" link (the identical DDL ships in carwash/barbershop as
 * {@code ticket_id}). Restaurant-service has TWO revenue-recording aggregates — {@code
 * restaurant_order} and {@code bill} — and reuses this one column for both: for an order-originated
 * deduction it holds the order id, and for a bill/guest-tab check it holds the {@code bill.id}.
 * There is no {@code REFERENCES} clause (this is an independent audit trail, not a CASCADE-owned
 * child), so either usage is schema-legal.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "applied_promotion")
public class AppliedPromotion extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "rule_id", nullable = false, updatable = false)
  private UUID ruleId;

  @Column(name = "coupon_id", updatable = false)
  private UUID couponId;

  @Column(name = "rule_name_snapshot", nullable = false, length = 120, updatable = false)
  private String ruleNameSnapshot;

  @Column(name = "rule_type_snapshot", nullable = false, length = 32, updatable = false)
  private String ruleTypeSnapshot;

  @Column(name = "rate_bp_snapshot", updatable = false)
  private Long rateBpSnapshot;

  @Column(name = "line_ref", updatable = false)
  private UUID lineRef;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "amount_minor", nullable = false, updatable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3, updatable = false))
  })
  private MoneyEmbeddable amount;

  protected AppliedPromotion() {
    // for JPA
  }

  /**
   * Creates an applied-promotion audit row with a freshly generated id.
   *
   * @param orderId the source aggregate id (a {@code restaurant_order.id} or a {@code bill.id} —
   *     see class javadoc)
   * @param saleId the sale this deduction ultimately rode on; {@code null} until the order/check
   *     captures a sale (digital-tender revenue-at-capture, ADR 0006)
   * @param ruleId the {@link PromoRule} that produced this deduction (never null — a manual
   *     discount is not a rule and never produces a row here)
   * @param couponId the {@link Coupon} that gated this deduction, or {@code null} for an automatic
   *     rule
   * @param ruleNameSnapshot the rule's name at application time
   * @param ruleTypeSnapshot the rule's type at application time
   * @param rateBpSnapshot the rule's basis-point rate at application time, or {@code null} for a
   *     fixed-amount rule
   * @param lineRef the {@code order_line}/{@code bill_line} id this deduction landed on, or {@code
   *     null} for an order-level rule
   * @param amount the actual amount discounted (already clamped per the composition rule)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public AppliedPromotion(
      UUID orderId,
      UUID saleId,
      UUID ruleId,
      UUID couponId,
      String ruleNameSnapshot,
      String ruleTypeSnapshot,
      Long rateBpSnapshot,
      UUID lineRef,
      Money amount) {
    this.id = UUID.randomUUID();
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.saleId = saleId;
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
    this.couponId = couponId;
    this.ruleNameSnapshot = Objects.requireNonNull(ruleNameSnapshot, "ruleNameSnapshot");
    this.ruleTypeSnapshot = Objects.requireNonNull(ruleTypeSnapshot, "ruleTypeSnapshot");
    this.rateBpSnapshot = rateBpSnapshot;
    this.lineRef = lineRef;
    this.amount = MoneyEmbeddable.of(Objects.requireNonNull(amount, "amount"));
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getSaleId() {
    return saleId;
  }

  /** Stamps the sale id once it is known (digital-tender capture path). */
  public void stampSaleId(UUID saleId) {
    this.saleId = Objects.requireNonNull(saleId, "saleId");
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public UUID getCouponId() {
    return couponId;
  }

  public String getRuleNameSnapshot() {
    return ruleNameSnapshot;
  }

  public String getRuleTypeSnapshot() {
    return ruleTypeSnapshot;
  }

  public Long getRateBpSnapshot() {
    return rateBpSnapshot;
  }

  public UUID getLineRef() {
    return lineRef;
  }

  public Money getAmount() {
    return amount.toMoney();
  }
}
