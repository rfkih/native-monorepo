package id.co.nativeapp.finance.platform.domain;

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
 * A {@code platform_settlement} row (ADR 0036 Phase C) — the append-only record of one platform
 * payout: "the channel settled {@code grossMinor} of its receivable; we received {@code netMinor};
 * the {@code feeMinor} difference is the commission", posted as {@link #journalEntryId} ({@code Dr
 * CASH_CLEARING net + Dr PLATFORM_FEE_EXPENSE fee / Cr PLATFORM_RECEIVABLE gross}). Settlements are
 * genuinely repeatable per channel (a platform pays out weekly), so uniqueness is per {@code
 * (company_id, idempotency_key)} (V45), not per channel.
 *
 * <p>{@code feeMinor} is derived ({@code gross − net}) but persisted for the immutable audit trail
 * — the V45 CHECK keeps it honest. Money rule 8: integer minor units + ISO-4217; never a float.
 * Extends {@link Auditable} (rule 4) and is under FORCE RLS (rule 5, V45).
 */
@Entity
@Table(name = "platform_settlement")
public class PlatformSettlement extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "channel_code", nullable = false, updatable = false, length = 32)
  private String channelCode;

  @Column(name = "gross_minor", nullable = false, updatable = false)
  private long grossMinor;

  @Column(name = "net_minor", nullable = false, updatable = false)
  private long netMinor;

  @Column(name = "fee_minor", nullable = false, updatable = false)
  private long feeMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "journal_entry_id", nullable = false, updatable = false)
  private UUID journalEntryId;

  @Column(name = "settled_at", nullable = false, updatable = false)
  private Instant settledAt;

  /** The Idempotency-Key that produced this settlement — the replay lookup key. */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  protected PlatformSettlement() {
    // for JPA
  }

  public PlatformSettlement(
      String channelCode,
      Money gross,
      Money net,
      UUID journalEntryId,
      Instant settledAt,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.channelCode = Objects.requireNonNull(channelCode, "channelCode");
    this.grossMinor = gross.amountMinor();
    this.netMinor = net.amountMinor();
    this.feeMinor = Math.subtractExact(gross.amountMinor(), net.amountMinor());
    this.currency = gross.currency().getCurrencyCode();
    this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  public UUID getId() {
    return id;
  }

  public String getChannelCode() {
    return channelCode;
  }

  public long getGrossMinor() {
    return grossMinor;
  }

  public long getNetMinor() {
    return netMinor;
  }

  public long getFeeMinor() {
    return feeMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }

  public Instant getSettledAt() {
    return settledAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
