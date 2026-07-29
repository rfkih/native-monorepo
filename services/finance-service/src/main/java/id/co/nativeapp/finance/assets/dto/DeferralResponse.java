package id.co.nativeapp.finance.assets.dto;

import java.util.UUID;

/**
 * One deferral-list row (Phase 6): the deferral plus the amount amortized to date and the remaining
 * balance ({@code total − amortized}). All amounts are minor units.
 */
public record DeferralResponse(
    UUID id,
    String kind,
    String description,
    long totalMinor,
    int months,
    String startPeriod,
    String currency,
    long amortizedMinor,
    long remainingMinor) {}
