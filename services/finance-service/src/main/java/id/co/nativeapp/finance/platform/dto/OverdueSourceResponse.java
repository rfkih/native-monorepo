package id.co.nativeapp.finance.platform.dto;

/**
 * A payer whose money has been sitting too long (ADR 0076 phase 3) — one row of the Beranda nudge.
 *
 * <p>{@code daysSinceLastPayout} is {@code null} when this payer has never paid out, which is the
 * loudest case: money has been accruing since the first sale and nothing has ever come back. {@code
 * cadenceDays} is the threshold it was judged against, so the console can say why it is being asked
 * rather than just that it is.
 */
public record OverdueSourceResponse(
    String sourceCode,
    String currency,
    long outstandingMinor,
    Long daysSinceLastPayout,
    int cadenceDays) {}
