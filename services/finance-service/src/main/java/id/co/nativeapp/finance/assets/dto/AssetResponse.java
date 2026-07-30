package id.co.nativeapp.finance.assets.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One asset-register row (Phase 6): the asset plus its accumulated depreciation and book value
 * ({@code cost − accumulated}). All amounts are minor units. {@code disposalDate} and {@code
 * proceedsMinor} are null until the asset is DISPOSED (ADR 0022).
 */
public record AssetResponse(
    UUID id,
    String name,
    LocalDate acquisitionDate,
    String startPeriod,
    long costMinor,
    long salvageMinor,
    int usefulLifeMonths,
    String currency,
    String status,
    LocalDate disposalDate,
    Long proceedsMinor,
    long accumulatedMinor,
    long bookValueMinor) {}
