package id.co.nativeapp.carwash.giftcard.service;

import id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse;

/**
 * Outcome of a {@code sell} call.
 *
 * @param sale the sold (or pre-existing) gift-card sale, as its response projection
 * @param created {@code true} if this call minted a new card + emitted {@code GiftCardSold}; {@code
 *     false} if an existing sale with the same {@code (company_id, idempotency_key)} was returned
 *     (idempotent retry — no second card minted, no second event)
 */
public record GiftCardSaleResult(GiftCardSaleResponse sale, boolean created) {}
