package id.co.nativeapp.carwash.wash.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over the {@code wash} row — only the columns a {@code WashResponse} needs, never
 * the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code WashRepository} (e.g. {@code
 * findViewByIdempotencyKey}, {@code findAllViews}). Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention (CLAUDE.md "native-query aliases
 * snake_case; map via projection interfaces"), so a read path fetches a narrow column set instead
 * of {@code SELECT *} of the full entity. Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 *
 * <p>The {@code currency} column is {@code CHAR(3)} in PostgreSQL and may carry trailing spaces;
 * callers must {@link String#strip()} it when building a response.
 */
public interface WashView {

  UUID getId();

  UUID getBusinessId();

  String getBay();

  long getAmountMinor();

  String getCurrency();

  String getUpsellName();

  Long getUpsellAmountMinor();

  Instant getOccurredAt();

  String getIdempotencyKey();
}
