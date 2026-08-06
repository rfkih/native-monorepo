package id.co.nativeapp.restaurant.stocktake.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for a {@code stocktake} header row (ADR 0038 phase 3) — backs the native
 * idempotency-replay probe, by-id read, and history queries on {@code StocktakeRepository}.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface StocktakeView {

  UUID getId();

  UUID getBusinessId();

  Instant getCountedAt();

  String getCurrency();

  long getShrinkageMinor();
}
