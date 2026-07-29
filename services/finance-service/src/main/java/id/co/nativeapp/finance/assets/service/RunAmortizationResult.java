package id.co.nativeapp.finance.assets.service;

import java.util.UUID;

/**
 * The outcome of {@link AmortizationRunWriter#run}: the run id and whether this call FRESHLY ran the
 * period ({@code created == true}) or found an already-run period and no-oped ({@code created ==
 * false}). Lets the controller honour the idempotent-POST contract (a fresh run → {@code 201}, a
 * re-run → {@code 200} with the existing resource — the tax-filing precedent).
 */
public record RunAmortizationResult(UUID runId, boolean created) {}
