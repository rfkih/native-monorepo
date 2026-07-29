package id.co.nativeapp.finance.assets.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One asset-register row (Phase 6): the asset plus its accumulated depreciation and book value
 * ({@code cost − accumulated}). All amounts are minor units.
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
    long accumulatedMinor,
    long bookValueMinor) {}
