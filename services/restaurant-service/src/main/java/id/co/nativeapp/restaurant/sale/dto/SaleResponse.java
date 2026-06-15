package id.co.nativeapp.restaurant.sale.dto;

import id.co.nativeapp.restaurant.sale.domain.Sale;
import java.time.Instant;
import java.util.UUID;

/** Record-sale response body. */
public record SaleResponse(
    UUID id,
    UUID businessId,
    long amountMinor,
    String currency,
    Instant occurredAt,
    String idempotencyKey) {

  public static SaleResponse from(Sale sale) {
    return new SaleResponse(
        sale.getId(),
        sale.getBusinessId(),
        sale.getAmount().amountMinor(),
        sale.getAmount().currency().getCurrencyCode(),
        sale.getOccurredAt(),
        sale.getIdempotencyKey());
  }
}
