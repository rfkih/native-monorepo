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

  /** Set for an order-originated payment; {@code null} for a bill-originated one (rule: XOR). */
  @Column(name = "order_id", updatable = false)
  private UUID orderId;

  /**
   * Set for a bill-originated payment (V38, dynamic QRIS/CARD gateway on a full-bill check); {@code
   * null} for an order-originated one. Exactly one of {@link #orderId}/{@link #billId} is ever set
   * — enforced both here ({@link #Payment} constructor) and by the {@code
   * ck_payment_order_xor_bill} DB CHECK.
   */
  @Column(name = "bill_id", updatable = false)
  private UUID billId;

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

  /**
   * When the most recent refund was applied (V21, ADR 0036) — the v1 attribution key for the
   * register close's cash-refunds window (a refund is counted in whichever session window contains
   * this instant). Null until the first refund. Approximation documented in ADR 0036: multiple
   * partial refunds keep only the LAST timestamp.
   */
  @Column(name = "last_refund_at")
  private Instant lastRefundAt;

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

  /**
   * The sales-channel code this tender rang through (V24, ADR 0036 Phase B2) — set ONLY by {@link
   * #capturedOnline}; every other factory leaves it {@code null}. A snapshot, not a foreign key
   * (see {@code sales_channel.code}'s deactivation semantics) — {@code updatable = false} because a
   * payment never changes which channel it settled through after capture.
   */
  @Column(name = "channel_code", updatable = false, length = 32)
  private String channelCode;

  /**
   * The operator's Keycloak {@code sub}, captured at RING TIME (ADR 0049 P4) — set by {@code
   * payment.service.PaymentWriter#recordPendingDigitalInCurrentTx} when a verified operator session
   * is present at checkout, for a PENDING digital (QRIS/CARD) tender ONLY. There is no live
   * operator session on the async {@code PaymentChargeSucceeded} capture thread (a Kafka consumer,
   * no HTTP request), so this column is what lets {@code PaymentCaptureWriter#capture} thread the
   * RING-TIME operator through to the sale's {@code sold_by_user_id} / commission metric subject
   * instead of falling back to the bound (device) actor. {@code null} for a CASH/ONLINE payment
   * (captured synchronously, where the sale itself is stamped directly by {@code SaleWriter}) and
   * for any checkout with no operator session present.
   */
  @Column(name = "sold_by_user_id", updatable = false)
  private String soldByUserId;

  /**
   * The manual (staff-entered) discount requested at pay-pending mint time, minor units (V38, bill
   * gateway only) — re-fed into the same promotions + tax computation at capture time so the
   * recomputed check breakdown reproduces the amount authorized here, deterministically. {@code
   * null} for an order-originated payment and for any bill payment minted with no discount.
   */
  @Column(name = "discount_minor", updatable = false)
  private Long discountMinor;

  protected Payment() {
    // for JPA
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Payment(
      UUID orderId,
      UUID billId,
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
      String idempotencyKey,
      String channelCode,
      Long discountMinor) {
    this.id = UUID.randomUUID();
    // Rule: exactly one of orderId/billId is ever set — mirrors the DB ck_payment_order_xor_bill
    // CHECK (V38) at the domain layer too, so a construction-time bug fails fast in-process rather
    // than only at flush.
    if ((orderId == null) == (billId == null)) {
      throw new IllegalArgumentException(
          "exactly one of orderId/billId must be set (order XOR bill payment); orderId="
              + orderId
              + " billId="
              + billId);
    }
    this.orderId = orderId;
    this.billId = billId;
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
    this.channelCode = channelCode;
    this.discountMinor = discountMinor;
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
        null,
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
        idempotencyKey,
        null,
        null);
  }

  /**
   * A live ONLINE (platform-collected) tender, already captured against {@code saleId} (ADR 0036
   * Phase B2) — the platform already holds the customer's money at acceptance, so it settles
   * SYNCHRONOUSLY like cash. Unlike {@link #capturedCash}, there is no separate tendered amount to
   * validate: the platform remitted exactly {@code amount}, so {@code tendered == amount} and
   * {@code change == 0} always (never client-supplied).
   *
   * @param channelCode the company-managed {@code sales_channel.code} this sale rang through
   *     (REQUIRED — the {@code ck_payment_online_requires_channel} CHECK backs this up at the DB)
   */
  public static Payment capturedOnline(
      UUID orderId,
      UUID businessId,
      Money amount,
      String channelCode,
      UUID saleId,
      Instant capturedAt,
      String idempotencyKey) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(channelCode, "channelCode");
    if (channelCode.isBlank()) {
      throw new IllegalArgumentException("channelCode must not be blank");
    }
    Objects.requireNonNull(saleId, "saleId");
    return new Payment(
        orderId,
        null,
        businessId,
        TenderType.ONLINE,
        Status.CAPTURED,
        amount,
        amount.amountMinor(),
        0L,
        false,
        null,
        saleId,
        capturedAt,
        capturedAt,
        idempotencyKey,
        channelCode,
        null);
  }

  /**
   * A flagged-pending digital tender (QRIS/card) against an ORDER: {@link Status#PENDING}, {@code
   * providerPending = true}, no sale yet. Records revenue only when {@link #capture(UUID, Instant)}
   * runs.
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
        null,
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
        idempotencyKey,
        null,
        null);
  }

  /**
   * A flagged-pending digital tender (QRIS/card) against a BILL (V38): {@link Status#PENDING},
   * {@code providerPending = true}, no sale yet. Mints the reservation counterpart the caller
   * ({@code BillWriter.initiatePendingPayment}) stamps onto the bill's still-unpaid {@code
   * bill_line} rows. Records revenue only when {@link #capture(UUID, Instant)} runs (via {@code
   * BillPaymentCaptureWriter}, not {@code PaymentCaptureWriter} — that class stays order-only).
   *
   * <p><strong>HIGH fix (code review).</strong> This factory does NOT accept — and {@link Payment}
   * does NOT store — a sale idempotency key. Storing one here would be minted BEFORE the payment's
   * own {@link #id} is known and, worse, is exactly what regressed to a bill-wide shared key in the
   * first cut (a second sale on the same bill collided on {@code uq_sale_company_idempotency}).
   * {@code BillPaymentCaptureWriter#capture} instead derives the check's sale idempotency key
   * on-the-fly from {@code this.getId()} at CAPTURE time — mirroring EXACTLY how the order path's
   * {@code PaymentCaptureWriter#capture} derives {@code payment.getId() + ":capture-sale"} — so it
   * is unique PER PAYMENT (never shared across a bill's multiple checks over its lifetime) by
   * construction, with no separate column to keep in sync.
   *
   * @param billId the bill this payment settles (never {@code null})
   * @param discountMinor the manual discount requested at mint time, minor units, or {@code null} —
   *     re-fed into the SAME promotions + tax computation at capture time so the recomputed grand
   *     total reproduces {@code amount} deterministically
   * @throws IllegalArgumentException if {@code billId} is {@code null} or {@code tenderType} is not
   *     digital
   */
  public static Payment pendingDigitalForBill(
      UUID billId,
      UUID businessId,
      TenderType tenderType,
      Money amount,
      Long discountMinor,
      String providerRef,
      Instant occurredAt,
      String idempotencyKey) {
    Objects.requireNonNull(billId, "billId");
    if (tenderType == null || !tenderType.isDigital()) {
      throw new IllegalArgumentException(
          "pendingDigitalForBill requires a digital tender: " + tenderType);
    }
    return new Payment(
        null,
        billId,
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
        idempotencyKey,
        null,
        discountMinor);
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

  /**
   * Abandons a PENDING tender that was never captured ({@link Status#PENDING} → {@link
   * Status#ABANDONED}) — never produces revenue (ADR 0006 invariant). Used by {@code
   * BillPaymentWriter#abandon} to release a stale/superseded gateway attempt (V38).
   *
   * @throws IllegalStateException if this payment is not currently PENDING
   */
  public void abandon() {
    if (status != Status.PENDING) {
      throw new IllegalStateException("only a PENDING payment can be abandoned; was " + status);
    }
    this.status = Status.ABANDONED;
  }

  /** Voids a captured tender ({@link Status#CAPTURED} → VOIDED) — a full reversal. */
  public void voidPayment() {
    if (status != Status.CAPTURED) {
      throw new PaymentNotReversibleException(
          "only a CAPTURED payment can be voided; was " + status);
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
      throw new PaymentNotReversibleException(
          "only a captured payment can be refunded; was " + status);
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
    this.lastRefundAt = Instant.now(); // ADR 0036: the register-close cash-refund window key
    this.status =
        newTotal.amountMinor() == captured.amountMinor()
            ? Status.REFUNDED
            : Status.PARTIALLY_REFUNDED;
    return newTotal;
  }

  public UUID getId() {
    return id;
  }

  /** {@code null} for a bill-originated payment (V38) — see {@link #isForBill()}. */
  public UUID getOrderId() {
    return orderId;
  }

  /** {@code null} for an order-originated payment — see {@link #isForBill()}. */
  public UUID getBillId() {
    return billId;
  }

  /**
   * {@code true} when this payment settles a {@code bill} (V38, dynamic QRIS/CARD gateway) rather
   * than an {@code order} — dispatches {@code PaymentChargeSucceededWriter} / {@code
   * PaymentCaptureService} to {@code BillPaymentCaptureWriter} instead of the order-only {@code
   * PaymentCaptureWriter}.
   */
  public boolean isForBill() {
    return billId != null;
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

  /**
   * The sales-channel code this tender rang through (ADR 0036 Phase B2) — set ONLY for an {@code
   * ONLINE} tender captured via {@link #capturedOnline}; {@code null} for every other tender.
   */
  public String getChannelCode() {
    return channelCode;
  }

  /**
   * The operator's Keycloak {@code sub} captured at ring time (ADR 0049 P4), or {@code null} — set
   * ONLY for a PENDING digital tender whose checkout carried a verified operator session.
   */
  public String getSoldByUserId() {
    return soldByUserId;
  }

  /**
   * The manual discount requested at pay-pending mint time, minor units (V38, bill gateway only),
   * or {@code null}. See {@link #pendingDigitalForBill} javadoc.
   */
  public Long getDiscountMinor() {
    return discountMinor;
  }

  /**
   * Stamps the verified operator's Keycloak {@code sub} onto this PENDING digital payment (ADR 0049
   * P4) — called by {@code PaymentWriter} on a freshly-constructed (not-yet-persisted) payment from
   * an {@code OperatorContextProvider} principal, NEVER from a client-supplied value (rule 5).
   * {@code PaymentCaptureWriter#capture} reads it back at async capture time to thread the
   * ring-time operator onto the sale.
   *
   * <p>Guarded to fire only when {@link #soldByUserId} is still {@code null}: the {@code
   * sold_by_user_id} column is {@code updatable=false}, so this is meant to run exactly once, at
   * creation.
   *
   * @param userId the operator's Keycloak {@code sub} — never a client-supplied value
   * @throws IllegalStateException if {@link #soldByUserId} is already stamped on this payment
   */
  public void stampSeller(String userId) {
    Objects.requireNonNull(userId, "userId");
    if (this.soldByUserId != null) {
      throw new IllegalStateException("soldByUserId is already stamped on this payment");
    }
    this.soldByUserId = userId;
  }
}
