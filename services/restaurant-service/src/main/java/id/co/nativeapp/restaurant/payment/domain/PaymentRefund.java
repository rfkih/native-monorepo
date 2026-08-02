package id.co.nativeapp.restaurant.payment.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per REFUND EVENT — the append-only refund ledger (V22, ADR 0036 review C3). {@code
 * payment.refunded_minor} stays the cumulative total (the domain guard), but window-scoped
 * consumers (the register close's cash-refunds sum) need the per-refund DELTA at its own
 * timestamp: attributing a cumulative total to one window double-counts partial refunds that span
 * sessions. Money rule 8: integer minor units + ISO-4217.
 */
@Entity
@Table(name = "payment_refund")
public class PaymentRefund extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  /** Tender snapshot from the payment — lets the cash-window query skip a join. */
  @Column(name = "tender_type", nullable = false, updatable = false)
  private String tenderType;

  /** The DELTA of THIS refund (not the cumulative), minor units, strictly positive. */
  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "refunded_at", nullable = false, updatable = false)
  private Instant refundedAt;

  protected PaymentRefund() {
    // for JPA
  }

  /** Appends one refund event for {@code payment} with the refund DELTA {@code amount}. */
  public PaymentRefund(Payment payment, Money amount, Instant refundedAt) {
    Objects.requireNonNull(payment, "payment");
    if (amount.amountMinor() <= 0) {
      throw new IllegalArgumentException("refund delta must be positive");
    }
    this.id = UUID.randomUUID();
    this.paymentId = payment.getId();
    this.businessId = payment.getBusinessId();
    this.tenderType = payment.getTenderType().name();
    this.amountMinor = amount.amountMinor();
    this.currency = amount.currency().getCurrencyCode();
    this.refundedAt = Objects.requireNonNull(refundedAt, "refundedAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }
}
