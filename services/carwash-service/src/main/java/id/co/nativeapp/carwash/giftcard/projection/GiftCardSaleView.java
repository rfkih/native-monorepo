package id.co.nativeapp.carwash.giftcard.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over a {@code gift_card_sale} row — only the columns a {@link
 * id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse} needs, never the {@link
 * id.co.nativeapp.tenant.Auditable Auditable} bookkeeping (CODE-STRUCTURE §3.3).
 */
public interface GiftCardSaleView {

  UUID getId();

  UUID getGiftCardId();

  UUID getBusinessId();

  long getAmountMinor();

  String getCurrency();

  String getTenderType();

  Instant getOccurredAt();

  String getIdempotencyKey();
}
