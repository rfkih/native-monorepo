package id.co.nativeapp.finance.assets.service;

import java.util.UUID;

/**
 * The outcome of {@link AssetDisposalWriter#dispose}: the disposed asset and whether THIS call
 * freshly posted the disposal ({@code created == true} → 201) or replayed a prior attempt with the
 * same Idempotency-Key ({@code created == false} → 200, nothing posted).
 */
public record DisposeAssetResult(UUID assetId, boolean created) {}
