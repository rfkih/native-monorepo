package id.co.nativeapp.restaurant.payment.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.MoneyEmbeddable;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code payment} aggregate — one tender against an order (ADR 0006).
 *
 * <p>The revenue-recognised-at-capture invariant lives here: a {@link TenderType#CASH} payment is
 * constructed already {@link Status#CAPTURED} (it links a {@code sale_id} in the same atomic
 * checkout transaction), while a digital ({@link TenderType#QRIS}/{@link TenderType#CARD}) payment
 * is constructed {@link Status#PENDING} with {@code providerPending = true} and <strong>no
 * sale</strong> — it only links a sale, and thus produces revenue, when {@link #capture(UUID,
 * Instant)} runs. So an abandoned digital tender never recognises revenue.
 *
 * <p>All money is {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217, never
 * a float): the tender {@link #amount}, plus the cash-only {@link #tenderedMinor}/{@link
 * #changeMinor} and the cumulative {@link #refundedMinor}, all in the payment's {@code currency}.
 * Change is never negative and a refund can never exceed the captured amount — enforced in the
 * aggregate and again by the {@code payment} CHECK constraints.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code payment} RLS policy (rule 5).
 */
@Entity
@Table(name = "payment")
public class Payment extends Auditable {

  /** Lifecycle of a tender. Stored as {@code EnumType.STRING}. */
  public enum Status {
    /** Digital tender awaiting capture; no sale, no revenue yet. */
    PENDING,
    /** Captured — a sale has been recorded; this is the revenue-bearing state. */
    CAPTURED,
    /** A captured tender fully reversed before settlement. */
    VOIDED,
    /** A captured tender fully refunded. */
    REFUNDED,
    /** A captured tender refunded in part (more refundable remains). */
    PARTIALLY_REFUNDED,
    /** A pending tender that was never captured and has been swept. */
    ABANDONED,
    /** A pending tender the provider reported as failed. */
    FAILED
  }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Enumerated(EnumType.STRING)
  @Column(name = "tender_type", nullable = false, updatable = false, length = 16)
  private TenderType tenderType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private Status status;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "tendered_minor")
  private Long tenderedMinor;

  @Column(name = "change_minor")
  private Long changeMinor;

  @Column(name = "refunded_minor", nullable = false)
  private long refundedMinor;

  @Column(name = "provider_ref", length = 128)
  private String providerRef;

  @Column(name = "provider_pending", nullable = false)
  private boolean providerPending;

  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "captured_at")
  private Instant capturedAt;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  protected Payment() {
    // for JPA
  }

  private Payment(
      UUID orderId,
      UUID businessId,
      TenderType tenderType,
      Status status,
      Money amount,
      Long tenderedMinor,
      Long changeMinor,
      boolean providerPending,
      String providerRef,
      UUID saleId,
      Instant capturedAt,
      Instant occurredAt,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.tenderType = Objects.requireNonNull(tenderType, "tenderType");
    this.status = Objects.requireNonNull(status, "status");
    this.amount = MoneyEmbeddable.of(amount);
    this.tenderedMinor = tenderedMinor;
    this.changeMinor = changeMinor;
    this.refundedMinor = 0L;
    this.providerPending = providerPending;
    this.providerRef = providerRef;
    this.saleId = saleId;
    this.capturedAt = capturedAt;
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /**
   * A live cash tender, already captured against {@code saleId}. {@code tendered} must cover {@code
   * amount} (the change, computed by the caller via {@link Money}, is therefore non-negative).
   */
  public static Payment capturedCash(
      UUID orderId,
      UUID businessId,
      Money amount,
      Money tendered,
      Money change,
      UUID saleId,
      Instant occurredAt,
      String idempotencyKey) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(tendered, "tendered");
    Objects.requireNonNull(change, "change");
    Objects.requireNonNull(saleId, "saleId");
    if (change.amountMinor() < 0L) {
      throw new IllegalArgumentException("change must be non-negative");
    }
    return new Payment(
        orderId,
        businessId,
        TenderType.CASH,
        Status.CAPTURED,
        amount,
        tendered.amountMinor(),
        change.amountMinor(),
        false,
        null,
        saleId,
        occurredAt,
        occurredAt,
        idempotencyKey);
  }

  /**
   * A flagged-pending digital tender (QRIS/card): {@link Status#PENDING}, {@code providerPending =
   * true}, no sale yet. Records revenue only when {@link #capture(UUID, Instant)} runs.
   */
  public static Payment pendingDigital(
      UUID orderId,
      UUID businessId,
      TenderType tenderType,
      Money amount,
      String providerRef,
      Instant occurredAt,
      String idempotencyKey) {
    if (tenderType == null || !tenderType.isDigital()) {
      throw new IllegalArgumentException("pendingDigital requires a digital tender: " + tenderType);
    }
    return new Payment(
        orderId,
        businessId,
        tenderType,
        Status.PENDING,
        amount,
        null,
        null,
        true,
        providerRef,
        null,
        null,
        occurredAt,
        idempotencyKey);
  }

  /** Captures a pending tender against a recorded sale ({@link Status#PENDING} → CAPTURED). */
  public void capture(UUID saleId, Instant capturedAt) {
    if (status != Status.PENDING) {
      throw new IllegalStateException("only a PENDING payment can be captured; was " + status);
    }
    this.saleId = Objects.requireNonNull(saleId, "saleId");
    this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    this.status = Status.CAPTURED;
  }

  /** Voids a captured tender ({@link Status#CAPTURED} → VOIDED) — a full reversal. */
  public void voidPayment() {
    if (status != Status.CAPTURED) {
      throw new IllegalStateException("only a CAPTURED payment can be voided; was " + status);
    }
    this.status = Status.VOIDED;
  }

  /**
   * Refunds {@code amount} of a captured tender, accumulating against prior refunds. The amount may
   * not exceed what remains refundable (captured amount minus prior refunds). Transitions to {@link
   * Status#REFUNDED} once fully refunded, otherwise {@link Status#PARTIALLY_REFUNDED}.
   *
   * @return the new total refunded as {@link Money}
   */
  public Money refund(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (status != Status.CAPTURED && status != Status.PARTIALLY_REFUNDED) {
      throw new IllegalStateException("only a captured payment can be refunded; was " + status);
    }
    if (amount.amountMinor() <= 0L) {
      throw new IllegalArgumentException("refund amount must be positive");
    }
    Money captured = getAmount();
    Money alreadyRefunded = Money.ofMinor(refundedMinor, captured.currency().getCurrencyCode());
    Money newTotal = alreadyRefunded.plus(amount); // throws on currency mismatch
    if (newTotal.amountMinor() > captured.amountMinor()) {
      throw new IllegalArgumentException("refund exceeds refundable remaining");
    }
    this.refundedMinor = newTotal.amountMinor();
    this.status =
        newTotal.amountMinor() == captured.amountMinor()
            ? Status.REFUNDED
            : Status.PARTIALLY_REFUNDED;
    return newTotal;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public TenderType getTenderType() {
    return tenderType;
  }

  public Status getStatus() {
    return status;
  }

  /** The tender amount as a {@link Money} value. */
  public Money getAmount() {
    return amount.toMoney();
  }

  public Long getTenderedMinor() {
    return tenderedMinor;
  }

  public Long getChangeMinor() {
    return changeMinor;
  }

  public long getRefundedMinor() {
    return refundedMinor;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public boolean isProviderPending() {
    return providerPending;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public Instant getCapturedAt() {
    return capturedAt;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
