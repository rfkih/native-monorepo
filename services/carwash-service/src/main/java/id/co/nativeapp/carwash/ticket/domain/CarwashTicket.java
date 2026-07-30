package id.co.nativeapp.carwash.ticket.domain;

import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code carwash_ticket} aggregate — the POS-parity checkout aggregate (V6, ADR 0023). Mirrors
 * restaurant's order+payment shape where concepts overlap; "ticket" replaces "order" as the carwash
 * checkout's aggregate name (one-shot checkout — no park/tabs/bills/KDS, ADR 0023 decision 1).
 *
 * <p><strong>Money.</strong> The five-leg price breakdown (subtotal, discount, service charge, tax,
 * total) shares a SINGLE {@code currency} column (V6 — unlike {@link
 * id.co.nativeapp.carwash.wash.domain.MoneyEmbeddable MoneyEmbeddable}, which pairs one amount with
 * one currency column, this aggregate needs five amounts against one shared currency), so each leg
 * is a plain {@code long} column and {@link #toBreakdown()} reconstructs the full {@link
 * PriceBreakdown} value type on demand — still integer minor units + ISO-4217, never a float (rule
 * 8). The DB {@code CHECK} constraint {@code subtotal - discount + service_charge + tax = total}
 * backstops the in-JVM {@link PriceBreakdown} reconciliation invariant.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code carwash_ticket} RLS policy
 * (rule 5). {@code (company_id, idempotency_key)} carries a UNIQUE constraint — the same
 * concurrency-safe idempotency contract {@code WashWriter} implements verbatim.
 */
@Entity
@Table(name = "carwash_ticket")
public class CarwashTicket extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "bay", nullable = false, updatable = false)
  private String bay;

  @Column(name = "vehicle_plate", updatable = false)
  private String vehiclePlate;

  @Column(name = "staff_profile_id", updatable = false)
  private UUID staffProfileId;

  @Column(name = "washer_employee_id", updatable = false)
  private UUID washerEmployeeId;

  @Column(name = "subtotal_minor", nullable = false, updatable = false)
  private long subtotalMinor;

  @Column(name = "discount_minor", nullable = false, updatable = false)
  private long discountMinor;

  @Column(name = "service_charge_minor", nullable = false, updatable = false)
  private long serviceChargeMinor;

  @Column(name = "tax_minor", nullable = false, updatable = false)
  private long taxMinor;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3, updatable = false)
  private String currency;

  @Column(name = "tax_rule_version", updatable = false)
  private String taxRuleVersion;

  @Column(name = "uses_illustrative_rules", nullable = false, updatable = false)
  private boolean usesIllustrativeRules;

  /**
   * The recorded sale this ticket produced. NULL until revenue is recognised: a CASH checkout
   * stamps this in the same transaction; a digital tender stamps it only when {@code capture} runs.
   */
  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  /**
   * Phase 3 (ADR 0026): the redeemed {@code coupon} id, if any. Denormalized convenience only — NOT
   * the source of truth for what discounted the ticket (the {@code applied_promotion} snapshot rows
   * are, and {@code discount_minor} above was computed from them). {@code null} means no coupon was
   * redeemed. Ported from restaurant-service's {@code Order.couponId} (V8 migration column).
   */
  @Column(name = "coupon_id")
  private UUID couponId;

  protected CarwashTicket() {
    // for JPA
  }

  /**
   * Creates a new ticket with a freshly generated id from a resolved {@link PriceBreakdown}.
   *
   * @param businessId the carwash outlet the ticket was opened at
   * @param bay the wash bay it ran on
   * @param vehiclePlate the optional vehicle plate; {@code null} for not recorded
   * @param staffProfileId the optional washer staff profile selected at checkout
   * @param washerEmployeeId the staff profile's linked employee id snapshot; {@code null} when
   *     unlinked or no profile was selected
   * @param breakdown the server-computed price breakdown (never trust a client amount)
   * @param occurredAt when the ticket was checked out
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public CarwashTicket(
      UUID businessId,
      String bay,
      String vehiclePlate,
      UUID staffProfileId,
      UUID washerEmployeeId,
      PriceBreakdown breakdown,
      Instant occurredAt,
      String idempotencyKey) {
    this(
        UUID.randomUUID(),
        businessId,
        bay,
        vehiclePlate,
        staffProfileId,
        washerEmployeeId,
        breakdown,
        occurredAt,
        idempotencyKey);
  }

  /**
   * Creates a new ticket with a CALLER-SUPPLIED id (Phase 3, ADR 0026). {@link
   * id.co.nativeapp.carwash.ticket.service.TicketWriter TicketWriter} pre-generates the ticket id
   * so the ticket's {@code carwash_ticket_line} rows — which carry a plain {@code ticket_id} FK
   * column rather than a JPA bidirectional association — can be built (and their own ids captured
   * for the promotions engine's {@code EvalLine.lineId}/{@code AppliedDeduction.lineRef}) BEFORE
   * this entity exists, since the engine must run before the {@link PriceBreakdown} this
   * constructor requires. The 8-arg overload above delegates here with a freshly generated id,
   * preserving every pre-Phase-3 call site.
   *
   * @param id the ticket's primary key, pre-generated by the caller
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public CarwashTicket(
      UUID id,
      UUID businessId,
      String bay,
      String vehiclePlate,
      UUID staffProfileId,
      UUID washerEmployeeId,
      PriceBreakdown breakdown,
      Instant occurredAt,
      String idempotencyKey) {
    this.id = Objects.requireNonNull(id, "id");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.bay = requireNonBlank(bay, "bay");
    this.vehiclePlate = vehiclePlate;
    this.staffProfileId = staffProfileId;
    this.washerEmployeeId = washerEmployeeId;
    Objects.requireNonNull(breakdown, "breakdown");
    this.subtotalMinor = breakdown.subtotal().amountMinor();
    this.discountMinor = breakdown.discount().amountMinor();
    this.serviceChargeMinor = breakdown.serviceCharge().amountMinor();
    this.taxMinor = breakdown.tax().amountMinor();
    this.totalMinor = breakdown.grandTotal().amountMinor();
    this.currency = breakdown.grandTotal().currency().getCurrencyCode();
    this.taxRuleVersion = breakdown.taxRuleVersion();
    this.usesIllustrativeRules = breakdown.usesIllustrativeRules();
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
  }

  /** Stamps the recorded sale id — revenue recognised (CASH at checkout, digital at capture). */
  public void linkSale(UUID saleId) {
    this.saleId = Objects.requireNonNull(saleId, "saleId");
  }

  /** Phase 3 (ADR 0026): stamps the redeemed coupon id. Called once, at the moment of redemption. */
  public void attachCoupon(UUID couponId) {
    this.couponId = Objects.requireNonNull(couponId, "couponId");
  }

  /** Phase 3 (ADR 0026): the redeemed coupon id, or {@code null} if none was used. */
  public UUID getCouponId() {
    return couponId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getBay() {
    return bay;
  }

  public String getVehiclePlate() {
    return vehiclePlate;
  }

  public UUID getStaffProfileId() {
    return staffProfileId;
  }

  public UUID getWasherEmployeeId() {
    return washerEmployeeId;
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

  /**
   * Reconstructs the full {@link PriceBreakdown} value from this ticket's persisted legs — used by
   * the capture path, which does not recompute pricing (the amount was already fixed and authorized
   * at checkout).
   */
  public PriceBreakdown toBreakdown() {
    Currency ccy = Currency.getInstance(currency.strip());
    Money subtotal = Money.ofMinor(subtotalMinor, ccy);
    Money discount = Money.ofMinor(discountMinor, ccy);
    Money serviceCharge = Money.ofMinor(serviceChargeMinor, ccy);
    Money tax = Money.ofMinor(taxMinor, ccy);
    Money grandTotal = Money.ofMinor(totalMinor, ccy);
    Money taxableBase = subtotal.minus(discount);
    return new PriceBreakdown(
        subtotal,
        discount,
        taxableBase,
        serviceCharge,
        tax,
        grandTotal,
        taxRuleVersion,
        usesIllustrativeRules);
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
