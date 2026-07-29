package id.co.nativeapp.finance.assets.dto;

import java.time.Instant;
import java.util.UUID;

/** One amortization-run history row (Phase 6). */
public record RunResponse(UUID id, String period, int lineCount, long totalMinor, Instant runAt) {}
