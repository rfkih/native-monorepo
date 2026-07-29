package id.co.nativeapp.finance.ap.domain;

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
 * A payment recorded against a {@link Bill} (Phase 2 AP). Each payment posts a balanced GL entry
 * (Dr AP / Cr cash-clearing) and advances the bill's {@code paid} total + status. Payments are
 * append-only — a correction is a new payment (or a void), never a mutation.
 *
 * <p>Extends {@link Auditable}; under the {@code bill_payment} RLS policy (V27).
 */
@Entity
@Table(name = "bill_payment")
public class BillPayment extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "bill_id", nullable = false, updatable = false)
  private UUID billId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "paid_at", nullable = false, updatable = false)
  private Instant paidAt;

  @Column(name = "method", nullable = false, updatable = false, length = 32)
  private String method;

  /** The GL journal entry raised for this payment (Dr AP / Cr cash-clearing). */
  @Column(name = "journal_entry_id", updatable = false)
  private UUID journalEntryId;

  /**
   * The optional client-supplied idempotency key, scoped UNIQUE per tenant + bill (V27, baked in
   * from the start — AR needed a follow-up V26 for the same mechanic). A retried payment with the
   * same key is replayed rather than double-posted.
   */
  @Column(name = "idempotency_key", updatable = false, length = 64)
  private String idempotencyKey;

  protected BillPayment() {
    // for JPA
  }

  /**
   * Builds a payment. The amount must be strictly positive; its currency is the payment's currency
   * (must match the bill currency — enforced by the caller). {@code idempotencyKey} is optional
   * (blank is normalised to {@code null}).
   */
  public static BillPayment of(
      UUID billId,
      Money amount,
      Instant paidAt,
      String method,
      UUID journalEntryId,
      String idempotencyKey) {
    Objects.requireNonNull(billId, "billId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(paidAt, "paidAt");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("payment amount must be strictly positive: " + amount);
    }
    BillPayment payment = new BillPayment();
    payment.id = UUID.randomUUID();
    payment.billId = billId;
    payment.amountMinor = amount.amountMinor();
    payment.currency = amount.currency().getCurrencyCode();
    payment.paidAt = paidAt;
    payment.method = normaliseMethod(method);
    payment.journalEntryId = journalEntryId;
    payment.idempotencyKey = blankToNull(idempotencyKey);
    return payment;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normaliseMethod(String method) {
    if (method == null) {
      return "CASH";
    }
    String trimmed = method.strip();
    return trimmed.isEmpty() ? "CASH" : trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBillId() {
    return billId;
  }

  /** The payment amount as a {@link Money} value. */
  public Money amount() {
    return Money.ofMinor(amountMinor, currency.strip());
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public String getMethod() {
    return method;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }
}
