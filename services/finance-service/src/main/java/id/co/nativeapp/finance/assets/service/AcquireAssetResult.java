package id.co.nativeapp.finance.assets.service;

import java.util.UUID;

/**
 * The outcome of {@link FixedAssetWriter#acquire}: the asset id and whether this call FRESHLY
 * acquired it ({@code created == true}) or replayed a prior attempt with the same Idempotency-Key
 * ({@code created == false} — nothing posted). Drives the controller's 201-vs-200.
 */
public record AcquireAssetResult(UUID assetId, boolean created) {}
