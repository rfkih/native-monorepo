package id.co.nativeapp.finance.revenue.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code SaleRecorded} event — the application command the consumer hands to the
 * posting service. An immutable record carrying exactly the fields finance needs from the contract,
 * already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}: the source
 * event id (used for idempotency), the owning tenant, the originating business, the sale amount as
 * {@link Money} (never a float), when it occurred (drives the period), the optional tender type
 * (ADR 0006 slice 2 — drives GL clearing-account routing), and the Phase 2 price breakdown fields.
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 *
 * <p>{@code tenderType} is the nullable tender string ({@code "CASH"}, {@code "QRIS"}, {@code
 * "CARD"}, or {@code null} for legacy/no-payment sales). Finance maps {@code null}/{@code "CASH"}
 * to {@code CASH_CLEARING}, {@code "QRIS"} to {@code QRIS_CLEARING}, and {@code "CARD"} to {@code
 * CARD_CLEARING} (the existing carwash / direct-sale paths remain unchanged).
 *
 * <p><strong>Phase 2 breakdown fields</strong> ({@code subtotalMinor}, {@code discountMinor},
 * {@code serviceChargeMinor}, {@code taxMinor}, {@code taxRuleVersion}, {@code
 * usesIllustrativeRules}) are all nullable. A legacy producer (carwash) leaves them all null;
 * finance falls back to {@code subtotal == amount} (the grand total), posts no
 * discount/service-charge/tax legs, and accumulates {@code subtotal} as net revenue — exactly the
 * Phase 1 behaviour. Finance NEVER recomputes the amounts — each basis just selects a field carried
 * on the event.
 *
 * <p><strong>Phase 4 loyalty/gift-card fields</strong> ({@code loyaltyMemberId}, {@code
 * loyaltyRedeemedPoints}, {@code loyaltyRedeemedMinor}, {@code giftCardId}, {@code
 * giftCardRedeemedMinor} — ADR 0027) are all nullable, appended at the end (positional decode
 * safety). {@code loyaltyRedeemedMinor} is a CONTRA-REVENUE deduction, exactly like {@code
 * discountMinor} — it extends {@link #assertReconciliationIdentity()}. {@code
 * giftCardRedeemedMinor} is a TENDER-SETTLEMENT amount (never a revenue deduction): it must be
 * {@code null} or in {@code (0, amount]} — enforced by {@link #assertReconciliationIdentity()} as
 * well, so a violation of either takes the same poison/DLT path as a Phase 2 identity violation. A
 * pre-Phase-4 event (all five fields null) evaluates byte-identically to before Phase 4 — every
 * {@code effective*} accessor below treats null as its Phase 1/2 default.
 *
 * <p><strong>{@code channel} field</strong> (Phase B, ADR 0036) is nullable, appended LAST after
 * the Phase 4 fields (positional decode safety). It carries the sales-channel code for an
 * ONLINE-tender sale; finance routes the ONLINE clearing debit to PLATFORM_RECEIVABLE and
 * accumulates a per-channel receivable sub-ledger under this code (a null channel on an ONLINE sale
 * accumulates under UNKNOWN rather than dropping money) — that routing lands with the tender value
 * itself in a LATER phase. In THIS wave every producer emits an explicit null, so finance never
 * actually observes a non-null channel yet; this record component and the decode path are
 * schema-first, exactly as the Phase 4 fields were.
 *
 * @param eventId the source event UUID (idempotency key — the outbox row UUID)
 * @param saleId the sale aggregate UUID from restaurant-service (the {@code sale_id} Avro field);
 *     used by the reversal writer to look up the original GL entry for per-leg unwind (Phase 2)
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating business unit
 * @param amount the GRAND TOTAL as {@link Money} (never a float) — the customer-pays amount
 * @param occurredAt when the sale occurred (drives the accounting period)
 * @param tenderType the optional tender type string, or null for legacy sales
 * @param subtotalMinor sum of line totals before discount/tax, or null for legacy producers
 * @param discountMinor order-level discount in minor units, or null (treated as 0)
 * @param serviceChargeMinor service charge in minor units, or null (treated as 0)
 * @param taxMinor tax amount in minor units, or null (treated as 0)
 * @param taxRuleVersion the rule_version label of the resolved tax rule, or null
 * @param usesIllustrativeRules true when any resolved rule was ILLUSTRATIVE_PLACEHOLDER, or null
 *     (treated as false)
 * @param loyaltyMemberId the loyalty member (UUID as string) this sale earns points for, or null
 *     (Phase 4, ADR 0027) — NOT PII, an opaque id; finance never reads this field
 * @param loyaltyRedeemedPoints the number of points spent on this sale, or null — finance IGNORES
 *     this field (not money); carried for traceability only
 * @param loyaltyRedeemedMinor the currency value of redeemed points in minor units, or null
 *     (treated as 0) — a contra-revenue deduction, like {@code discountMinor}
 * @param giftCardId the gift card (UUID as string) redeemed as a tender, or null — finance never
 *     reads this field directly (it is a loyalty-service concern); carried for traceability
 * @param giftCardRedeemedMinor the stored-value amount redeemed from the gift card in minor units,
 *     or null (treated as 0) — a TENDER-SETTLEMENT amount, never a revenue deduction; must be
 *     {@code <= amount_minor} when present
 * @param channel the sales-channel code for an ONLINE-tender sale (e.g. {@code GOFOOD}, {@code
 *     GRABFOOD}), or null (Phase B, ADR 0036) — null for every non-ONLINE tender and for every
 *     producer in this wave (no producer threads a real channel yet; that lands in Phase B2).
 *     Appended LAST (positional decode safety, the same discipline the Phase 4 fields follow).
 */
public record SaleRecordedEvent(
    UUID eventId,
    UUID saleId,
    String companyId,
    UUID businessId,
    Money amount,
    Instant occurredAt,
    String tenderType,
    Long subtotalMinor,
    Long discountMinor,
    Long serviceChargeMinor,
    Long taxMinor,
    String taxRuleVersion,
    Boolean usesIllustrativeRules,
    String loyaltyMemberId,
    Long loyaltyRedeemedPoints,
    Long loyaltyRedeemedMinor,
    String giftCardId,
    Long giftCardRedeemedMinor,
    String channel) {

  /**
   * Backward-compatible constructor for callers that pre-date the Phase 2 breakdown fields (e.g.
   * tests written before Phase 2, or legacy producers). Sets saleId to null, all breakdown fields
   * to null, tender type to null, all Phase 4 loyalty/gift-card fields to null, and {@code channel}
   * (Phase B, ADR 0036) to null.
   */
  public SaleRecordedEvent(
      UUID eventId, String companyId, UUID businessId, Money amount, Instant occurredAt) {
    this(
        eventId,
        null,
        companyId,
        businessId,
        amount,
        occurredAt,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Backward-compatible constructor for callers that have a tender type but no breakdown (Phase 1).
   * Sets saleId, all Phase 2 breakdown fields, all Phase 4 loyalty/gift-card fields, and {@code
   * channel} (Phase B, ADR 0036) to null.
   */
  public SaleRecordedEvent(
      UUID eventId,
      String companyId,
      UUID businessId,
      Money amount,
      Instant occurredAt,
      String tenderType) {
    this(
        eventId,
        null,
        companyId,
        businessId,
        amount,
        occurredAt,
        tenderType,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Backward-compatible constructor for callers built against the Phase 2 shape (before Phase 4,
   * ADR 0027, added the five trailing loyalty/gift-card fields). Sets all five Phase 4 fields AND
   * {@code channel} (Phase B, ADR 0036) to null — a Phase 2 caller (or a pre-Phase-4 producer's
   * decoded record) is therefore byte-identical in behaviour to before Phase 4: {@link
   * #effectiveLoyaltyRedeemed()} and {@link #effectiveGiftCardRedeemed()} both resolve to zero, and
   * {@link #assertReconciliationIdentity()} reduces to exactly the Phase 2 identity.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public SaleRecordedEvent(
      UUID eventId,
      UUID saleId,
      String companyId,
      UUID businessId,
      Money amount,
      Instant occurredAt,
      String tenderType,
      Long subtotalMinor,
      Long discountMinor,
      Long serviceChargeMinor,
      Long taxMinor,
      String taxRuleVersion,
      Boolean usesIllustrativeRules) {
    this(
        eventId,
        saleId,
        companyId,
        businessId,
        amount,
        occurredAt,
        tenderType,
        subtotalMinor,
        discountMinor,
        serviceChargeMinor,
        taxMinor,
        taxRuleVersion,
        usesIllustrativeRules,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Resolves the effective subtotal: if {@code subtotalMinor} is present, return it as a {@link
   * Money}; otherwise fall back to {@code amount} (grand total == subtotal for legacy events with
   * no discount/tax/service-charge breakdown).
   */
  public Money effectiveSubtotal() {
    if (subtotalMinor != null) {
      return Money.ofMinor(subtotalMinor, amount.currency().getCurrencyCode());
    }
    return amount;
  }

  /** Resolves the effective discount: zero when {@code discountMinor} is null. */
  public Money effectiveDiscount() {
    if (discountMinor != null) {
      return Money.ofMinor(discountMinor, amount.currency().getCurrencyCode());
    }
    return Money.ofMinor(0L, amount.currency().getCurrencyCode());
  }

  /** Resolves the effective service charge: zero when {@code serviceChargeMinor} is null. */
  public Money effectiveServiceCharge() {
    if (serviceChargeMinor != null) {
      return Money.ofMinor(serviceChargeMinor, amount.currency().getCurrencyCode());
    }
    return Money.ofMinor(0L, amount.currency().getCurrencyCode());
  }

  /** Resolves the effective tax: zero when {@code taxMinor} is null. */
  public Money effectiveTax() {
    if (taxMinor != null) {
      return Money.ofMinor(taxMinor, amount.currency().getCurrencyCode());
    }
    return Money.ofMinor(0L, amount.currency().getCurrencyCode());
  }

  /**
   * Whether this event uses illustrative rules. Returns false when {@code usesIllustrativeRules} is
   * null (legacy producers).
   */
  public boolean effectiveUsesIllustrative() {
    return Boolean.TRUE.equals(usesIllustrativeRules);
  }

  /**
   * Resolves the effective redeemed-loyalty-points VALUE (Phase 4, ADR 0027): zero when {@code
   * loyaltyRedeemedMinor} is null (no redemption, or a pre-Phase-4 producer). A CONTRA-REVENUE
   * deduction — used by {@link #assertReconciliationIdentity()} and by the finance net-revenue
   * accumulation exactly like {@link #effectiveDiscount()}.
   */
  public Money effectiveLoyaltyRedeemed() {
    if (loyaltyRedeemedMinor != null) {
      return Money.ofMinor(loyaltyRedeemedMinor, amount.currency().getCurrencyCode());
    }
    return Money.ofMinor(0L, amount.currency().getCurrencyCode());
  }

  /**
   * Resolves the effective gift-card-redeemed TENDER amount (Phase 4, ADR 0027): zero when {@code
   * giftCardRedeemedMinor} is null (no gift card used, or a pre-Phase-4 producer). A
   * TENDER-SETTLEMENT amount — never a revenue deduction, unlike {@link #effectiveDiscount()} or
   * {@link #effectiveLoyaltyRedeemed()}. Used by the {@code NET_TENDER}/{@code GIFT_CARD_TENDER}
   * basis resolution ({@code JournalPostingService}) to split the SALE clearing debit.
   */
  public Money effectiveGiftCardRedeemed() {
    if (giftCardRedeemedMinor != null) {
      return Money.ofMinor(giftCardRedeemedMinor, amount.currency().getCurrencyCode());
    }
    return Money.ofMinor(0L, amount.currency().getCurrencyCode());
  }

  /**
   * Asserts the finance-side reconciliation identity — Phase 4 (ADR 0027) EXTENDS the Phase 2
   * identity with the loyalty contra-revenue deduction: {@code subtotal − discount −
   * loyaltyRedeemed + serviceCharge + tax == grandTotal}. Also enforces the Phase 4 gift-card
   * TENDER guard (see {@link #assertGiftCardGuard()}) — both checks are poison-event/DLT triggers
   * from a misconfigured producer.
   *
   * <p>The reconciliation identity is only checked when breakdown fields are present (i.e. not a
   * legacy/carwash event). A legacy event (all-null breakdown) trivially satisfies the identity as
   * {@code subtotal == grandTotal}; a pre-Phase-4 Phase 2 event (null {@code loyaltyRedeemedMinor})
   * reduces to EXACTLY the Phase 2 identity ({@code loyaltyRedeemed == 0}), so this method is
   * byte-identical in behaviour to before Phase 4 for every existing caller. The gift-card guard
   * runs unconditionally (it does not depend on the breakdown fields being present).
   *
   * @throws IllegalStateException if either check is violated
   */
  public void assertReconciliationIdentity() {
    assertGiftCardGuard();
    if (subtotalMinor == null) {
      // Legacy event: no breakdown to check; subtotal == grand total by definition.
      return;
    }
    String ccy = amount.currency().getCurrencyCode();
    Money subtotal = Money.ofMinor(subtotalMinor, ccy);
    Money discount = effectiveDiscount();
    Money loyaltyRedeemed = effectiveLoyaltyRedeemed();
    Money serviceCharge = effectiveServiceCharge();
    Money tax = effectiveTax();
    Money expected = subtotal.minus(discount).minus(loyaltyRedeemed).plus(serviceCharge).plus(tax);
    if (!expected.equals(amount)) {
      throw new IllegalStateException(
          "SaleRecorded reconciliation identity violated: subtotal("
              + subtotalMinor
              + ") - discount("
              + (discountMinor == null ? 0 : discountMinor)
              + ") - loyaltyRedeemed("
              + (loyaltyRedeemedMinor == null ? 0 : loyaltyRedeemedMinor)
              + ") + serviceCharge("
              + (serviceChargeMinor == null ? 0 : serviceChargeMinor)
              + ") + tax("
              + (taxMinor == null ? 0 : taxMinor)
              + ") = "
              + expected.amountMinor()
              + " != grandTotal("
              + amount.amountMinor()
              + ") — poison event, routing to DLT");
    }
  }

  /**
   * Phase 4 (ADR 0027) gift-card TENDER guard: when {@code giftCardRedeemedMinor} is present it
   * must be strictly positive and no greater than the grand total ({@code amount_minor}) — a
   * gift-card redemption can never exceed what the customer owes. {@code null} (no gift card used,
   * or a pre-Phase-4 producer) is a no-op, so this guard never fires for an event that predates
   * Phase 4.
   *
   * @throws IllegalStateException if {@code giftCardRedeemedMinor <= 0} or {@code >
   *     amount.amountMinor()}
   */
  private void assertGiftCardGuard() {
    if (giftCardRedeemedMinor == null) {
      return;
    }
    if (giftCardRedeemedMinor <= 0L) {
      throw new IllegalStateException(
          "SaleRecorded gift_card_redeemed_minor must be > 0 when present; got "
              + giftCardRedeemedMinor
              + " — poison event, routing to DLT");
    }
    if (giftCardRedeemedMinor > amount.amountMinor()) {
      throw new IllegalStateException(
          "SaleRecorded gift_card_redeemed_minor ("
              + giftCardRedeemedMinor
              + ") must be <= amount_minor ("
              + amount.amountMinor()
              + ") — poison event, routing to DLT");
    }
  }
}
