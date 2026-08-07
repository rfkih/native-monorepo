package id.co.nativeapp.payment.charge.domain;

import id.co.nativeapp.payment.settings.domain.PspProvider;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payment_charge} aggregate (ADR 0045) — one dynamic-QRIS PSP charge against a
 * vertical's PENDING payment, on the merchant's own Midtrans account. Created {@link
 * ChargeStatus#INITIATED} and committed BEFORE the outbound PSP call (a webhook can never race an
 * uncommitted row); {@link #markSucceeded} is the ONLY transition that pairs with a {@code
 * PaymentChargeSucceeded} outbox row, and the caller relies on the JPA optimistic {@code version}
 * to make a concurrent webhook/sync settle exactly once.
 *
 * <p>Money is minor units + ISO-4217 (rule 8; IDR-only enforced by the writer). No PII lives here —
 * the merchant's credentials stay on {@code payment_settings}. Extends {@link Auditable} (rule 4);
 * under the {@code payment_charge} RLS policy (rule 5, V3).
 */
@Entity
@Table(name = "payment_charge")
public class PaymentCharge extends Auditable {

  /** Midtrans order_id prefix — {@code provider_order_id = "n-" + id} (≤ 50 chars). */
  public static final String ORDER_ID_PREFIX = "n-";

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "vertical", nullable = false, length = 16, updatable = false)
  private String vertical;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  @Column(name = "reference_id", updatable = false)
  private UUID referenceId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3, updatable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ChargeStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 16, updatable = false)
  private PspProvider provider;

  @Column(name = "provider_order_id", nullable = false, length = 50, updatable = false)
  private String providerOrderId;

  @Column(name = "provider_txn_id", length = 64)
  private String providerTxnId;

  @Column(name = "qr_string")
  private String qrString;

  @Column(name = "qr_url")
  private String qrUrl;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "succeeded_at")
  private Instant succeededAt;

  @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
  private String idempotencyKey;

  protected PaymentCharge() {
    // for JPA
  }

  /** Creates an INITIATED charge — the pre-PSP-call anchor row. */
  public PaymentCharge(
      String vertical,
      UUID paymentId,
      UUID referenceId,
      UUID businessId,
      long amountMinor,
      String currency,
      PspProvider provider,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.vertical = Objects.requireNonNull(vertical, "vertical");
    this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
    this.referenceId = referenceId;
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive, was " + amountMinor);
    }
    this.amountMinor = amountMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.providerOrderId = ORDER_ID_PREFIX + this.id;
    this.status = ChargeStatus.INITIATED;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /** INITIATED → QR_ISSUED: Midtrans accepted the charge and returned the QR payload. */
  public void markQrIssued(String qrString, String qrUrl, String providerTxnId, Instant expiresAt) {
    requireLive("issue a QR for");
    this.qrString = qrString;
    this.qrUrl = qrUrl;
    this.providerTxnId = providerTxnId;
    this.expiresAt = expiresAt;
    this.status = ChargeStatus.QR_ISSUED;
  }

  /**
   * live → SUCCEEDED — the ONLY event-bearing transition. The caller writes the {@code
   * PaymentChargeSucceeded} outbox row in the same transaction and relies on the optimistic version
   * to settle a webhook/sync race exactly once.
   */
  public void markSucceeded(String providerTxnId, Instant succeededAt) {
    requireLive("settle");
    if (providerTxnId != null) {
      this.providerTxnId = providerTxnId;
    }
    this.succeededAt = Objects.requireNonNull(succeededAt, "succeededAt");
    this.status = ChargeStatus.SUCCEEDED;
  }

  /** live → EXPIRED (Midtrans expiry, or the lazy past-expiry sweep on read). */
  public void markExpired() {
    requireLive("expire");
    this.status = ChargeStatus.EXPIRED;
  }

  /** live → CANCELED (cashier cancel, confirmed or unknown at the PSP). */
  public void markCanceled() {
    requireLive("cancel");
    this.status = ChargeStatus.CANCELED;
  }

  /** live → FAILED (PSP deny/failure, or the create call never yielded a QR). */
  public void markFailed() {
    requireLive("fail");
    this.status = ChargeStatus.FAILED;
  }

  private void requireLive(String action) {
    if (!status.isLive()) {
      throw new ChargeStateException("Cannot " + action + " a " + status + " charge.");
    }
  }

  public boolean isLive() {
    return status.isLive();
  }

  public UUID getId() {
    return id;
  }

  public String getVertical() {
    return vertical;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getReferenceId() {
    return referenceId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public ChargeStatus getStatus() {
    return status;
  }

  public PspProvider getProvider() {
    return provider;
  }

  public String getProviderOrderId() {
    return providerOrderId;
  }

  public String getProviderTxnId() {
    return providerTxnId;
  }

  public String getQrString() {
    return qrString;
  }

  public String getQrUrl() {
    return qrUrl;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getSucceededAt() {
    return succeededAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @Override
  public String toString() {
    return "PaymentCharge[id="
        + id
        + ", vertical="
        + vertical
        + ", paymentId="
        + paymentId
        + ", status="
        + status
        + ", amountMinor="
        + amountMinor
        + "]";
  }
}
