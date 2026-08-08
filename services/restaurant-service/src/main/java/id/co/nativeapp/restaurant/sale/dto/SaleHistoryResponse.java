package id.co.nativeapp.restaurant.sale.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row of the cashier's "today's transactions" list ({@code GET /api/v1/sales}).
 *
 * @param saleId the sale's id
 * @param orderId the linked order id (reprint link), or {@code null} when no order backs this sale
 *     (e.g. a bar-tab/carwash/direct sale)
 * @param occurredAt when the sale occurred
 * @param amountMinor the sale amount in the currency's minor units (never a float)
 * @param currency the ISO-4217 currency code
 * @param tenderType the settling tender ({@code CASH | QRIS | CARD}), or {@code null} for
 *     legacy/no-payment sales
 * @param channelCode the sales-channel code this sale rang through, or {@code null} for a
 *     non-ONLINE tender
 */
public record SaleHistoryResponse(
    UUID saleId,
    UUID orderId,
    Instant occurredAt,
    long amountMinor,
    String currency,
    String tenderType,
    String channelCode) {}
