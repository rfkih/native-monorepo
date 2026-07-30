package id.co.nativeapp.barbershop.promotion.domain;

import id.co.nativeapp.barbershop.catalog.domain.MoneyEmbeddable;
import id.co.nativeapp.money.Money;
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
 * keep meaning exactly what it meant at application time even if the {@link PromoRule}/{@link Coupon}
 * row is later edited or retired. Maps to the {@code applied_promotion} table (V3__promotions.sql).
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code applied_promotion_tenant_isolation}
 * RLS policy (rule 5). {@code amount_minor}/{@code currency} use {@link MoneyEmbeddable} (rule 8)
 * since an applied-promotion row always carries the actual minor-unit amount it discounted.
 *
 * <p><strong>Port note (mirrors carwash-service, not restaurant directly).</strong> Restaurant's
 * {@code AppliedPromotion} names this column {@code order_id} (that service has two revenue
 * aggregates sharing the column). Barbershop, like carwash, has exactly one: {@code
 * barbershop_ticket}. The V3 migration therefore names the column {@code ticket_id} directly, and
 * this field is named {@code ticketId} to match. There is no {@code REFERENCES} clause (this is an
 * independent audit trail, not a CASCADE-owned child) — same as restaurant/carwash.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "applied_promotion")
public class AppliedPromotion extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

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
   * @param ticketId the source {@code barbershop_ticket.id}
   * @param saleId the sale this deduction ultimately rode on; {@code null} until the ticket captures
   *     a sale (digital-tender revenue-at-capture, ADR 0006/0023/0024); {@code sale_id == ticket_id}
   *     for barbershop once known (ADR 0023 decision 2, preserved by ADR 0024)
   * @param ruleId the {@link PromoRule} that produced this deduction (never null — a manual discount
   *     is not a rule and never produces a row here)
   * @param couponId the {@link Coupon} that gated this deduction, or {@code null} for an automatic
   *     rule
   * @param ruleNameSnapshot the rule's name at application time
   * @param ruleTypeSnapshot the rule's type at application time
   * @param rateBpSnapshot the rule's basis-point rate at application time, or {@code null} for a
   *     fixed-amount rule
   * @param lineRef the {@code barbershop_ticket_line} id this deduction landed on, or {@code null}
   *     for a ticket-level rule
   * @param amount the actual amount discounted (already clamped per the composition rule)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public AppliedPromotion(
      UUID ticketId,
      UUID saleId,
      UUID ruleId,
      UUID couponId,
      String ruleNameSnapshot,
      String ruleTypeSnapshot,
      Long rateBpSnapshot,
      UUID lineRef,
      Money amount) {
    this.id = UUID.randomUUID();
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
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

  public UUID getTicketId() {
    return ticketId;
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
