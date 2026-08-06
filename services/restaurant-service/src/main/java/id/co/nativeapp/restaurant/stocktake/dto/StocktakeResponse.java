package id.co.nativeapp.restaurant.stocktake.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response body for a stocktake submission or read (ADR 0038 phase 3). {@code shrinkageMinor} is
 * the SIGNED net valued shrinkage carried on {@code StocktakeCompleted} — positive = net loss,
 * negative = net gain, zero = no ledger entry.
 */
public record StocktakeResponse(
    UUID id,
    UUID businessId,
    String currency,
    Instant countedAt,
    long shrinkageMinor,
    List<StocktakeLineResponse> lines) {}
