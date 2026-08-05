package id.co.nativeapp.finance.reversal.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A parked sale reversal (QA sweep 2026-08-05, HIGH fix — the finance twin of loyalty's parking
 * slot): a {@code SaleVoided}/{@code SaleRefunded} consumed BEFORE its {@code SaleRecorded} posted
 * (cross-topic reorder). The old behavior took the GROSS template fall-back — structurally wrong
 * for any sale with tax/service-charge/discount/gift-card legs, and amount-wrong for gift-card
 * voids — then the real SALE entry posted on top. Now nothing posts at park time; {@code
 * RevenuePostingWriter} applies this row per-leg in the same transaction the sale posts in.
 *
 * <p>The parked event's fields are stored verbatim so it can be rebuilt exactly at apply time.
 * {@code outcome}: APPLIED (contra posted) or REJECTED_PARTIAL (the live path's partial-refund 400
 * equivalent — resolved with a WARN, never poisoning the sale-posting transaction).
 */
@Entity
@Table(name = "pending_sale_reversal")
public class PendingSaleReversal extends Auditable {

  /** How the parked row was resolved at apply time. */
  public enum Outcome {
    APPLIED,
    REJECTED_PARTIAL
  }

  /** Which reversal event kind was parked. */
  public enum Kind {
    VOIDED,
    REFUNDED
  }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "sale_id", nullable = false, updatable = false)
  private UUID saleId;

  @Column(name = "kind", nullable = false, length = 8, updatable = false)
  private String kind;

  @Column(name = "reversal_event_id", nullable = false, updatable = false)
  private UUID reversalEventId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3, updatable = false)
  private String currency;

  @Column(name = "total_refunded_minor", updatable = false)
  private Long totalRefundedMinor;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "tender_type", length = 32, updatable = false)
  private String tenderType;

  @Column(name = "channel", length = 64, updatable = false)
  private String channel;

  @Column(name = "business_id", updatable = false)
  private UUID businessId;

  @Column(name = "applied_at")
  private Instant appliedAt;

  @Column(name = "outcome", length = 16)
  private String outcome;

  protected PendingSaleReversal() {
    // for JPA — rows are only ever created via the repository's native parkIfAbsent
  }

  public UUID getId() {
    return id;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public Kind getKind() {
    return Kind.valueOf(kind);
  }

  public UUID getReversalEventId() {
    return reversalEventId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public Long getTotalRefundedMinor() {
    return totalRefundedMinor;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getTenderType() {
    return tenderType;
  }

  public String getChannel() {
    return channel;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public boolean isApplied() {
    return appliedAt != null;
  }

  public void markApplied(Instant at, Outcome resolution) {
    this.appliedAt = at;
    this.outcome = resolution.name();
  }
}
