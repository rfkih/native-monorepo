package id.co.nativeapp.restaurant.inventory.projection;

import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Read projection for an {@code ingredient_stocktake} header row (ADR 0046 phase 1) — backs the
 * native idempotency-replay probe, by-id read, and history queries on {@code
 * IngredientStocktakeRepository}.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface IngredientStocktakeView {

  UUID getId();

  UUID getBusinessId();

  Instant getCountedAt();

  /** {@code null} when the count carried zero costed lines (ADR 0046). */
  @Nullable String getCurrency();

  long getShrinkageMinor();
}
