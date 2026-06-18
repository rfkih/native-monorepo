package id.co.nativeapp.restaurant.sale.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over the {@code sale} row — only the columns a {@code SaleResponse} needs, never
 * the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code SaleRepository} (e.g. {@code
 * findViewByIdempotencyKey}, {@code findAllViews}). Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention (CLAUDE.md "native-query aliases
 * snake_case; map via projection interfaces"), so a read path fetches a narrow column set instead
 * of {@code SELECT *} of the full entity. Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface SaleView {

  UUID getId();

  UUID getBusinessId();

  long getAmountMinor();

  String getCurrency();

  Instant getOccurredAt();

  String getIdempotencyKey();
}
