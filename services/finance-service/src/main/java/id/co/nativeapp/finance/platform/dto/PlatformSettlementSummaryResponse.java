package id.co.nativeapp.finance.platform.dto;

/**
 * One channel's platform-settlement summary for a period ({@code GET
 * /api/v1/platform-settlements/summary}) — the period's settled gross/fee/net totals (money rule 8:
 * integer minor units + ISO-4217 code, never a float) and settlement count, for a single {@code
 * (channelCode, currency)} bucket (ADR 0036 Phase C).
 *
 * @param channelCode the sales-channel code (e.g. {@code GOFOOD}, {@code GRABFOOD})
 * @param settledGrossMinor the sum of {@code gross_minor} across the channel's settlements in the
 *     period, minor units
 * @param feeMinor the sum of {@code fee_minor} (the platform's commission) in the period
 * @param netMinor the sum of {@code net_minor} (cash actually received) in the period
 * @param settlementCount the number of settlements that rolled into this bucket
 * @param currency the ISO-4217 currency code
 */
public record PlatformSettlementSummaryResponse(
    String channelCode,
    long settledGrossMinor,
    long feeMinor,
    long netMinor,
    long settlementCount,
    String currency) {}
