package id.co.nativeapp.finance.labor.service;

import java.util.UUID;

/**
 * The result of {@link PayrollSettlementWriter#settle}: the settlement id, and whether THIS call
 * freshly posted it ({@code true}) or replayed an existing settlement via a repeated
 * Idempotency-Key ({@code false}) — mirrors {@code DisposeAssetResult}/{@code AcquireAssetResult}
 * (ADR 0032, Track P phase P5), driving the controller's 201-vs-200 idempotent-POST contract.
 */
public record PayrollSettlementResult(UUID settlementId, boolean created) {}
