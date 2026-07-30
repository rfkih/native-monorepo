package id.co.nativeapp.carwash.payment.domain;

import id.co.nativeapp.carwash.wash.domain.MoneyEmbeddable;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code carwash_payment} aggregate — one tender against a carwash ticket (modeled on
 * restaurant-service's {@code payment.domain.Payment}, ADR 0006, but coupled to a {@code ticket_id}
 * rather than an order; column shape matches the V6 migration exactly).
 *
 * <p><strong>Status is narrower than the column's CHECK constraint on purpose.</strong> The V6
 * {@code carwash_payment.status} CHECK allows all seven of restaurant's {@code Payment.Status}
 * values (mirrored column-for-column from restaurant V3 by the migration author), but THIS PHASE
 * ports only the capture-inside-checkout foundation — no void/refund flow (restaurant's {@code
 * VoidRefundService} / {@code SaleVoided}/{@code SaleRefunded} are deliberately NOT ported) — so
 * {@link Status} declares only the two values this phase's code ever writes: {@code PENDING} and
 * {@code CAPTURED}. Both are within the DB CHECK's allowed set, so this is a valid (if narrower)
 * subset; a later phase can widen the enum to VOIDED/REFUNDED/etc. without a migration change.
 *
 * <p>The revenue-recognised-at-capture invariant lives here: a {@link TenderType#CASH} payment is
 * constructed already {@link Status#CAPTURED}, while a digital ({@link TenderType#QRIS}/{@link
 * TenderType#CARD}) payment is constructed {@link Status#PENDING} with {@code providerPending =
 * true} — it moves to {@link Status#CAPTURED} only when {@link #capture()} runs (wired by the
 * future ticket-checkout feature, out of scope here).
 *
 * <p>All money is {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217, never
 * a float): the tender {@link #amount}, plus the cash-only {@link #tenderedMinor}/{@link
 * #changeMinor}, in the payment's currency. Change is never negative — enforced in the aggregate.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code carwash_payment} RLS policy
 * (rule 5). Reuses {@code wash.domain.MoneyEmbeddable} rather than declaring a second copy — the
 * same precedent restaurant-service's {@code Payment} follows by reusing {@code
 * sale.domain.MoneyEmbeddable} (both persist the identical {@code amount_minor}/{@code currency}
 * shape, so one {@code @Embeddable} per service is enough).
 */
@Entity
@Table(name = "carwash_payment")
public class CarwashPayment extends Auditable {

  /** See the class-level note on why this enum is narrower than the column's CHECK constraint. */
  public enum Status {
    /** Digital tender awaiting capture; no revenue yet. */
    PENDING,
    /** Captured — the revenue-bearing state. */
    CAPTURED
  }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  /**
   * The carwash outlet (org_unit) this payment was taken at. Denormalized for reporting/audit; the
   * outlet-assignment guard runs ONCE at checkout ({@code TicketWriter}), not again at capture —
   * deliberately matching restaurant, whose {@code PaymentCaptureWriter} also runs no outlet guard
   * (capture completes a checkout that was already outlet-authorized; review S2).
   */
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

  @Column(name = "provider_ref", length = 255)
  private String providerRef;

  @Column(name = "provider_pending", nullable = false)
  private boolean providerPending;

  protected CarwashPayment() {
    // for JPA
  }

  private CarwashPayment(
      UUID ticketId,
      UUID businessId,
      TenderType tenderType,
      Status status,
      Money amount,
      Long tenderedMinor,
      Long changeMinor,
      boolean providerPending,
      String providerRef) {
    this.id = UUID.randomUUID();
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.tenderType = Objects.requireNonNull(tenderType, "tenderType");
    this.status = Objects.requireNonNull(status, "status");
    this.amount = MoneyEmbeddable.of(amount);
    this.tenderedMinor = tenderedMinor;
    this.changeMinor = changeMinor;
    this.providerPending = providerPending;
    this.providerRef = providerRef;
  }

  /**
   * A live cash tender, already captured. {@code tendered} must cover {@code amount} (the change,
   * computed by the caller via {@link Money}, is therefore non-negative).
   */
  public static CarwashPayment capturedCash(
      UUID ticketId, UUID businessId, Money amount, Money tendered, Money change) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(tendered, "tendered");
    Objects.requireNonNull(change, "change");
    if (change.amountMinor() < 0L) {
      throw new IllegalArgumentException("change must be non-negative");
    }
    return new CarwashPayment(
        ticketId,
        businessId,
        TenderType.CASH,
        Status.CAPTURED,
        amount,
        tendered.amountMinor(),
        change.amountMinor(),
        false,
        null);
  }

  /**
   * A flagged-pending digital tender (QRIS/card): {@link Status#PENDING}, {@code providerPending =
   * true}. Moves to {@link Status#CAPTURED} only when {@link #capture()} runs.
   */
  public static CarwashPayment pendingDigital(
      UUID ticketId, UUID businessId, TenderType tenderType, Money amount, String providerRef) {
    if (tenderType == null || !tenderType.isDigital()) {
      throw new IllegalArgumentException("pendingDigital requires a digital tender: " + tenderType);
    }
    return new CarwashPayment(
        ticketId, businessId, tenderType, Status.PENDING, amount, null, null, true, providerRef);
  }

  /** Captures a pending tender ({@link Status#PENDING} → CAPTURED). */
  public void capture() {
    if (status != Status.PENDING) {
      throw new IllegalStateException("only a PENDING payment can be captured; was " + status);
    }
    this.status = Status.CAPTURED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
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

  public String getProviderRef() {
    return providerRef;
  }

  public boolean isProviderPending() {
    return providerPending;
  }
}
