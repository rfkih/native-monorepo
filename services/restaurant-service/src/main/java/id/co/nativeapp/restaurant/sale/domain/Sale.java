package id.co.nativeapp.restaurant.sale.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code sale} aggregate — restaurant-service's system of record for a recorded sale, and the
 * source of the {@code SaleRecorded} event.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code sale} RLS policy in the Flyway baseline (rule 4 + rule
 * 5).
 *
 * <p>The monetary amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units +
 * ISO-4217 currency, never a float), persisted via {@link MoneyEmbeddable} as {@code amount_minor
 * BIGINT} + {@code currency CHAR(3)}.
 *
 * <p>{@code idempotency_key} is the client's request id; together with {@code company_id} it
 * carries a {@code UNIQUE} constraint so a retried record-sale resolves to the same row (producer
 * idempotency — exactly one {@code SaleRecorded} on retry).
 */
@Entity
@Table(name = "sale")
public class Sale extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  /**
   * The tender that settled this sale ({@code CASH | QRIS | CARD}, later {@code ONLINE}) — V21, ADR
   * 0036 (closing kasir). Nullable: legacy pre-V21 rows and no-payment paths have no tender; a NULL
   * row is simply outside every register session's cash window. Deliberately a String (not the
   * payment feature's enum) — the sale aggregate stores what the wire carries.
   */
  @Column(name = "tender_type", updatable = false)
  private String tenderType;

  /**
   * The CASH physically entering the drawer for a CASH-tender sale (V22, review C1): grand total
   * minus any gift-card-redeemed portion. A gift-card-split sale collects only the residual —
   * summing amount_minor would overstate expected drawer cash and post phantom shortages. Null for
   * legacy rows and non-cash tenders; the register query falls back to amount_minor via COALESCE.
   */
  @Column(name = "cash_collected_minor", updatable = false)
  private Long cashCollectedMinor;

  /**
   * The gift-card-redeemed portion of this sale (V27, ADR 0038 phase 2) — the NET amount that
   * accrued in a NON-cash tender's clearing account is {@code amount_minor − this} (finance debits
   * the clearing with the net tender). Recording it lets the register close reconcile a card/QRIS
   * sale that carried a gift-card split without a phantom short. 0 when no gift card (and for
   * legacy pre-V27 rows via the metadata default).
   */
  @Column(name = "gift_card_redeemed_minor", nullable = false, updatable = false)
  private long giftCardRedeemedMinor;

  /**
   * The sales-channel code this sale rang through (V24, ADR 0036 Phase B2) — set ONLY when {@link
   * #tenderType} is {@code "ONLINE"} (the writer enforces the pairing; no CHECK constraint, mirrors
   * {@link #tenderType}/{@link #cashCollectedMinor}'s rationale). A SNAPSHOT, not a foreign key —
   * survives the channel later being deactivated (or renamed) without retroactively invalidating
   * historical sales. Null for every other tender and for legacy pre-V24 rows.
   */
  @Column(name = "channel_code", updatable = false)
  private String channelCode;

  protected Sale() {
    // for JPA
  }

  /**
   * Creates a new sale with a freshly generated id.
   *
   * @param businessId the originating business unit
   * @param amount the sale amount as {@link Money} (never a float)
   * @param occurredAt when the sale occurred
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public Sale(UUID businessId, Money amount, Instant occurredAt, String idempotencyKey) {
    this(businessId, amount, occurredAt, idempotencyKey, null);
  }

  /**
   * Creates a new sale with a freshly generated id and the settling tender (ADR 0036).
   *
   * @param tenderType the tender enum name ({@code CASH | QRIS | CARD}), or null for
   *     legacy/no-payment sales
   */
  public Sale(
      UUID businessId, Money amount, Instant occurredAt, String idempotencyKey, String tenderType) {
    this(businessId, amount, occurredAt, idempotencyKey, tenderType, null);
  }

  /**
   * Constructor incl. the drawer-cash figure (review C1), no channel (pre-Phase-B2 callers).
   *
   * @param cashCollectedMinor the cash physically collected (grand total − gift-card portion) for a
   *     CASH sale; null for non-cash/legacy
   */
  public Sale(
      UUID businessId,
      Money amount,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType,
      Long cashCollectedMinor) {
    this(businessId, amount, occurredAt, idempotencyKey, tenderType, cashCollectedMinor, null, 0L);
  }

  /**
   * Full constructor incl. the drawer-cash figure (review C1) and the sales channel (V24, ADR 0036
   * Phase B2).
   *
   * @param cashCollectedMinor the cash physically collected (grand total − gift-card portion) for a
   *     CASH sale; null for non-cash/legacy
   * @param channelCode the sales-channel code this sale rang through; set ONLY for an {@code
   *     ONLINE}-tender sale, null otherwise
   * @param giftCardRedeemedMinor the gift-card-redeemed portion of the sale (≥ 0; 0 when none) —
   *     the non-cash clearing leg is {@code amount − this} (V27, ADR 0038 phase 2)
   */
  public Sale(
      UUID businessId,
      Money amount,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType,
      Long cashCollectedMinor,
      String channelCode,
      long giftCardRedeemedMinor) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.amount = MoneyEmbeddable.of(amount);
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.tenderType = tenderType;
    this.cashCollectedMinor = cashCollectedMinor;
    this.channelCode = channelCode;
    if (giftCardRedeemedMinor < 0) {
      throw new IllegalArgumentException("giftCardRedeemedMinor must be >= 0");
    }
    this.giftCardRedeemedMinor = giftCardRedeemedMinor;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  /** The sale amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /** The settling tender ({@code CASH | QRIS | CARD}), or null for legacy/no-payment sales. */
  public String getTenderType() {
    return tenderType;
  }

  /** Drawer cash collected for a CASH sale (grand total − gift-card portion), or null. */
  public Long getCashCollectedMinor() {
    return cashCollectedMinor;
  }

  /** The gift-card-redeemed portion of this sale (≥ 0; 0 when none) — V27, ADR 0038 phase 2. */
  public long getGiftCardRedeemedMinor() {
    return giftCardRedeemedMinor;
  }

  /**
   * The sales-channel code this sale rang through (ADR 0036 Phase B2) — set ONLY for an {@code
   * ONLINE}-tender sale, null otherwise.
   */
  public String getChannelCode() {
    return channelCode;
  }
}
