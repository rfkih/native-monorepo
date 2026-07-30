package id.co.nativeapp.carwash.giftcard.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a gift-card sale — the minted card's id, its DERIVED human-facing code (so the
 * till can print it immediately, before {@code GiftCardStateChanged} replicates back from
 * loyalty-service), and the recorded sale's own id/amount.
 *
 * @param giftCardSaleId this sale-record's own id (the {@code GiftCardSold} idempotency key)
 * @param giftCardId the minted card's id
 * @param code the card's derived human-facing code ({@link
 *     id.co.nativeapp.carwash.giftcard.domain.GiftCardCodeGenerator})
 * @param businessId the outlet the card was sold at
 * @param amountMinor the stored value loaded onto the card, minor units
 * @param currency ISO-4217 currency code
 * @param tenderType the tender used, or {@code null}
 * @param occurredAt when the sale occurred
 */
public record GiftCardSaleResponse(
    UUID giftCardSaleId,
    UUID giftCardId,
    String code,
    UUID businessId,
    long amountMinor,
    String currency,
    String tenderType,
    Instant occurredAt) {}
