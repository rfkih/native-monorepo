package id.co.nativeapp.finance.giftcard.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code GiftCardSold} event (ADR 0027, Phase 4 of the POS-parity program) — the
 * application command the consumer hands to {@code GiftCardPostingService}. An immutable record
 * carrying exactly the fields finance needs from the contract, already parsed out of the raw Avro
 * {@link org.apache.avro.generic.GenericRecord}.
 *
 * <p><strong>NOT a sale — a LIABILITY event.</strong> A gift card sold/topped up is stored value
 * the company now owes the bearer; it is never merged into {@code SaleRecorded} and finance never
 * accumulates it as revenue (no {@code consolidated_revenue}/{@code outlet_revenue}/{@code
 * consolidated_pnl} update — see {@code GiftCardPostingWriter}). Revenue is recognised later, at
 * redemption, via {@code SaleRecorded.gift_card_redeemed_minor}.
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 *
 * @param giftCardSaleId this gift-card-sale event's unique id (UUID as string on the wire) — the
 *     idempotency/dedupe key via {@code ProcessedEventStore}, and the {@code journal_entry
 *     source_event_id} (UNIQUE) database backstop
 * @param giftCardId the gift card aggregate id being sold/topped up — carried for traceability; not
 *     read by the GL posting itself (finance has no gift-card sub-ledger of its own; that lives in
 *     loyalty-service)
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating business unit / outlet
 * @param amount the stored-value amount sold/loaded, as {@link Money} (never a float)
 * @param occurredAt when the sale/top-up occurred (drives the accounting period)
 * @param tenderType the payment tender used to purchase/top up the card ({@code "CASH"}, {@code
 *     "QRIS"}, {@code "CARD"}), or {@code null} for legacy/unspecified — routes the offsetting
 *     clearing-account debit exactly like {@code SaleRecorded.tender_type}
 */
public record GiftCardSoldEvent(
    UUID giftCardSaleId,
    UUID giftCardId,
    String companyId,
    UUID businessId,
    Money amount,
    Instant occurredAt,
    String tenderType) {}
