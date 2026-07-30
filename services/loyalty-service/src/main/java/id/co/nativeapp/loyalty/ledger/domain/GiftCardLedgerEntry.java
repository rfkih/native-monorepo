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
 * The {@code gift_card_ledger_entry} aggregate — an APPEND-ONLY row recording one stored-value
 * movement (load/sale, redeem, reversal, or manual adjust) against a {@code gift_card}. Same
 * append-only + idempotency-backstop discipline as {@link LoyaltyLedgerEntry} (see its class
 * javadoc): {@code UNIQUE(company_id, source_event_id, entry_type)}.
 *
 * <p>{@code amountMinor} is a signed DELTA in the card's currency's minor units — positive for
 * {@code LOAD}, negative for {@code REDEEM}/a redemption {@code REVERSE} (which gives the value
 * back), of either sign for a manual {@code ADJUST}. Never a float (rule 8).
 */
@Entity
@Table(name = "gift_card_ledger_entry")
public class GiftCardLedgerEntry extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "gift_card_id", nullable = false)
  private UUID giftCardId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 16)
  private GiftCardLedgerEntryType entryType;

  /** Signed delta in the card currency's minor units (positive LOAD, negative REDEEM). */
  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /** The sale this REDEEM/REVERSE entry is attributed to, or {@code null} for a LOAD/ADJUST. */
  @Column(name = "sale_id")
  private UUID saleId;

  /** The causing event's durable id — the idempotency backstop key (see class javadoc). */
  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  protected GiftCardLedgerEntry() {
    // for JPA
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  public GiftCardLedgerEntry(
      UUID giftCardId,
      GiftCardLedgerEntryType entryType,
      long amountMinor,
      String currency,
      UUID saleId,
      UUID sourceEventId) {
    this.id = UUID.randomUUID();
    this.giftCardId = Objects.requireNonNull(giftCardId, "giftCardId");
    this.entryType = Objects.requireNonNull(entryType, "entryType");
    this.amountMinor = amountMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.saleId = saleId;
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
  }

  public UUID getId() {
    return id;
  }

  public UUID getGiftCardId() {
    return giftCardId;
  }

  public GiftCardLedgerEntryType getEntryType() {
    return entryType;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency.strip();
  }

  public UUID getSaleId() {
    return saleId;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }
}
