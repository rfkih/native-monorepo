package id.co.nativeapp.restaurant.sale.dto;

/**
 * One channel's ONLINE sales summary for a period ({@code GET /api/v1/sales/channel-summary}) —
 * gross sales (money rule 8: integer minor units + ISO-4217 code, never a float) and the
 * transaction count, for a single {@code (channelCode, currency)} bucket.
 *
 * @param channelCode the sales-channel code (e.g. {@code GOFOOD}, {@code GRABFOOD})
 * @param grossSalesMinor the sum of {@code amount_minor} across the channel's ONLINE sales in the
 *     period, in the currency's minor units (never a float)
 * @param transactionCount the number of ONLINE sales that rolled into this bucket
 * @param currency the ISO-4217 currency code
 */
public record ChannelSalesSummaryResponse(
    String channelCode, long grossSalesMinor, long transactionCount, String currency) {}
