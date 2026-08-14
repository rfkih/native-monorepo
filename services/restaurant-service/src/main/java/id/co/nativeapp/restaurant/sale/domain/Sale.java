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

  /**
   * The seller's Keycloak {@code sub}, captured at ring time (V33, ADR 0049 P0) — distinct from the
   * inherited {@link Auditable#getCreatedBy() createdBy}, which will record the DEVICE once outlet
   * credentials exist (ADR 0049 P3). Deliberately NOT threaded into any constructor: P0 is an inert
   * wire, so every existing call site is unchanged and this stays {@code null} until ADR 0049 P2
   * (the PIN / operator-session flow) starts populating it.
   */
  @Column(name = "sold_by_user_id", updatable = false)
  private String soldByUserId;

  /**
   * Reporting snapshot of the Phase 2 price breakdown computed at ring time (V39) — the SAME
   * figures emitted on {@code SaleRecorded} (see {@link
   * id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema}), persisted here so the POS daily
   * summary (Z-report) aggregates them with exact SQL sums instead of re-deriving the pricing
   * engine per order. {@code updatable=false}: stamped exactly once at creation via {@link
   * #stampBreakdown}. NULL for legacy/pre-V39 rows and for producers that carry no breakdown (the
   * legacy {@code POST /api/v1/sales}, carwash) — readers fall back to {@code subtotal ==
   * amount_minor}, mirroring finance's documented fallback. {@link #discountMinor} is PROMO-ONLY (a
   * loyalty-points redemption is a separate contra-revenue term, not folded in here), matching the
   * event wire's {@code discount_minor} decomposition.
   */
  @Column(name = "subtotal_minor", updatable = false)
  private Long subtotalMinor;

  @Column(name = "discount_minor", updatable = false)
  private Long discountMinor;

  @Column(name = "service_charge_minor", updatable = false)
  private Long serviceChargeMinor;

  @Column(name = "tax_minor", updatable = false)
  private Long taxMinor;

  /**
   * Reporting snapshot (V39) of the loyalty-points redemption on this sale — a CONTRA-REVENUE term
   * kept SEPARATE from {@link #discountMinor} (which is promo-only), matching the {@code
   * SaleRecorded} wire. Persisting it lets the daily summary reconcile the full identity {@code
   * gross − discount − loyaltyRedeemed + serviceCharge + tax = total}. {@code 0}/NULL when no
   * points were redeemed.
   */
  @Column(name = "loyalty_redeemed_minor", updatable = false)
  private Long loyaltyRedeemedMinor;

  @Column(name = "uses_illustrative_rules", updatable = false)
  private Boolean usesIllustrativeRules;

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

  /**
   * The seller's Keycloak {@code sub} captured at ring time (ADR 0049), or {@code null} — pre-P2
   * this is {@code null} for every sale (P0 is an inert wire; nothing sets this field yet).
   */
  public String getSoldByUserId() {
    return soldByUserId;
  }

  /**
   * Stamps the verified operator's Keycloak {@code sub} as this sale's seller (ADR 0049 P2) —
   * called by {@code SaleWriter} on a freshly-constructed (not-yet-persisted) sale, from an {@code
   * OperatorContextProvider} principal, NEVER from a client-supplied value (rule 5).
   *
   * <p>Guarded to fire only when {@link #soldByUserId} is still {@code null}: the {@code
   * sold_by_user_id} column is {@code updatable=false} (see the field javadoc), so this is meant to
   * run exactly once, at creation. The guard makes that invariant explicit and fails loudly instead
   * of silently no-op'ing at the database level on a later, mistaken call against an already-saved
   * sale.
   *
   * @param userId the operator's Keycloak {@code sub} — never a client-supplied value
   * @throws IllegalStateException if {@link #soldByUserId} is already stamped on this sale
   */
  public void stampSeller(String userId) {
    Objects.requireNonNull(userId, "userId");
    if (this.soldByUserId != null) {
      throw new IllegalStateException("soldByUserId is already stamped on this sale");
    }
    this.soldByUserId = userId;
  }

  /**
   * Stamps the Phase 2 price-breakdown reporting snapshot onto this sale (V39) — called by {@code
   * SaleWriter} on a freshly-constructed (not-yet-persisted) sale, BEFORE the first save (the
   * columns are {@code updatable=false}). The values are the SAME figures put on the {@code
   * SaleRecorded} event: {@code discountMinor} must be PROMO-ONLY (the caller subtracts any loyalty
   * redemption first, exactly as {@code SaleRecordedSchema#toRecord} does), so the sale row, the
   * event, and the receipt all agree. Reporting-only — finance's GL remains the authoritative
   * statutory figure.
   *
   * <p>Guarded to run exactly once: a second call (e.g. against an already-stamped sale) throws
   * loudly instead of silently no-op'ing at the {@code updatable=false} column level.
   *
   * @throws IllegalStateException if a breakdown snapshot is already stamped on this sale
   */
  public void stampBreakdown(
      long subtotalMinor,
      long discountMinor,
      long serviceChargeMinor,
      long taxMinor,
      long loyaltyRedeemedMinor,
      boolean usesIllustrativeRules) {
    if (this.subtotalMinor != null) {
      throw new IllegalStateException("a price-breakdown snapshot is already stamped on this sale");
    }
    this.subtotalMinor = subtotalMinor;
    this.discountMinor = discountMinor;
    this.serviceChargeMinor = serviceChargeMinor;
    this.taxMinor = taxMinor;
    this.loyaltyRedeemedMinor = loyaltyRedeemedMinor;
    this.usesIllustrativeRules = usesIllustrativeRules;
  }

  /**
   * Reporting snapshot (V39): Σ line totals before discount, minor units — {@code null} if legacy.
   */
  public Long getSubtotalMinor() {
    return subtotalMinor;
  }

  /** Reporting snapshot (V39): promo-only discount, minor units — {@code null} if legacy. */
  public Long getDiscountMinor() {
    return discountMinor;
  }

  /** Reporting snapshot (V39): service charge, minor units — {@code null} if legacy. */
  public Long getServiceChargeMinor() {
    return serviceChargeMinor;
  }

  /** Reporting snapshot (V39): tax / PB1, minor units — {@code null} if legacy. */
  public Long getTaxMinor() {
    return taxMinor;
  }

  /**
   * Reporting snapshot (V39): loyalty-points contra-revenue, minor units — {@code null} if legacy.
   */
  public Long getLoyaltyRedeemedMinor() {
    return loyaltyRedeemedMinor;
  }

  /**
   * Reporting snapshot (V39): whether an {@code ILLUSTRATIVE_PLACEHOLDER} tax/service-charge rule
   * produced these figures — the POS badges the printed tax line "estimasi" when true. {@code null}
   * for legacy rows.
   */
  public Boolean getUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }
}
