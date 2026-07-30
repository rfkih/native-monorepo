package id.co.nativeapp.loyalty.ledger.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code loyalty_ledger_entry} aggregate — an APPEND-ONLY row recording one points movement
 * (earn, redeem, manual adjust, or reversal) against a {@code loyalty_member}. Never updated or
 * deleted once written; a correction is a NEW row (an {@code ADJUST} or {@code REVERSE}), exactly
 * like a financial ledger.
 *
 * <p><strong>Idempotency backstop.</strong> {@code UNIQUE(company_id, source_event_id, entry_type)}
 * — the DB-level defense-in-depth guard behind the primary {@code ProcessedEventStore.processOnce}
 * dedupe (rule 3 / HR-3): {@code source_event_id} is the causing Kafka event's durable id (the
 * outbox {@code id} header), so a genuine re-delivery that somehow bypassed {@code processOnce}
 * still cannot double-insert the SAME entry type for the SAME causing event.
 *
 * <p>{@code valueMinor}/{@code currency} are optional: populated with the redeemed currency value
 * on a {@code REDEEM} entry (mirroring {@code SaleRecorded.loyalty_redeemed_minor}), and — for
 * traceability only — with the sale's taxable base a {@code EARN} entry was computed against; both
 * {@code null} for a points-only {@code ADJUST}.
 */
@Entity
@Table(name = "loyalty_ledger_entry")
public class LoyaltyLedgerEntry extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "member_id", nullable = false)
  private UUID memberId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 16)
  private LoyaltyLedgerEntryType entryType;

  /** Positive for EARN, negative for REDEEM/REVERSE (or an ADJUST of either sign). */
  @Column(name = "points_delta", nullable = false)
  private long pointsDelta;

  @Column(name = "value_minor")
  private Long valueMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", length = 3)
  private String currency;

  /** The sale this entry is attributed to, or {@code null} for an out-of-band manual adjustment. */
  @Column(name = "sale_id")
  private UUID saleId;

  /** The causing event's durable id — the idempotency backstop key (see class javadoc). */
  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  protected LoyaltyLedgerEntry() {
    // for JPA
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  public LoyaltyLedgerEntry(
      UUID memberId,
      LoyaltyLedgerEntryType entryType,
      long pointsDelta,
      Long valueMinor,
      String currency,
      UUID saleId,
      UUID sourceEventId) {
    this.id = UUID.randomUUID();
    this.memberId = Objects.requireNonNull(memberId, "memberId");
    this.entryType = Objects.requireNonNull(entryType, "entryType");
    this.pointsDelta = pointsDelta;
    this.valueMinor = valueMinor;
    this.currency = currency;
    this.saleId = saleId;
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
  }

  public UUID getId() {
    return id;
  }

  public UUID getMemberId() {
    return memberId;
  }

  public LoyaltyLedgerEntryType getEntryType() {
    return entryType;
  }

  public long getPointsDelta() {
    return pointsDelta;
  }

  public Long getValueMinor() {
    return valueMinor;
  }

  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public UUID getSaleId() {
    return saleId;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }
}
