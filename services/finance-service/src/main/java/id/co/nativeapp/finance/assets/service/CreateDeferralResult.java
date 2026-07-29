package id.co.nativeapp.finance.assets.service;

import java.util.UUID;

/**
 * The outcome of {@link DeferralWriter#create}: the deferral id and whether this call FRESHLY
 * created it ({@code created == true}) or replayed a prior attempt with the same Idempotency-Key
 * ({@code created == false} — nothing posted). Drives the controller's 201-vs-200.
 */
public record CreateDeferralResult(UUID deferralId, boolean created) {}
