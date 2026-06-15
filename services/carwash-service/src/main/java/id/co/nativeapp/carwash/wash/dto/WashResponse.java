package id.co.nativeapp.carwash.wash.dto;

import id.co.nativeapp.carwash.wash.domain.Wash;
import java.time.Instant;
import java.util.UUID;

/** Record-wash response body. */
public record WashResponse(
    UUID id,
    UUID businessId,
    String bay,
    long amountMinor,
    String currency,
    String upsellName,
    Long upsellAmountMinor,
    Instant occurredAt,
    String idempotencyKey) {

  public static WashResponse from(Wash wash) {
    return new WashResponse(
        wash.getId(),
        wash.getBusinessId(),
        wash.getBay(),
        wash.getAmount().amountMinor(),
        wash.getAmount().currency().getCurrencyCode(),
        wash.hasUpsell() ? wash.getUpsellName() : null,
        wash.hasUpsell() ? wash.getUpsellAmountMinor() : null,
        wash.getOccurredAt(),
        wash.getIdempotencyKey());
  }
}
