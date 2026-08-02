package id.co.nativeapp.finance.platform.dto;

/**
 * One channel's outstanding platform receivable (ADR 0036 Phase C). {@code outstandingMinor} may be
 * NEGATIVE (refund clawback after a full settlement — tolerated by design, V44).
 */
public record PlatformOutstandingResponse(
    String channelCode, String currency, long outstandingMinor) {}
