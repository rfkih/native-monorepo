package id.co.nativeapp.barbershop.catalog.projection;

import java.util.UUID;

/**
 * Read projection over a {@code service_item} or {@code service_addon} row — only the columns a
 * {@link id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse CatalogItemResponse} needs,
 * never the {@code Auditable} bookkeeping (CODE-STRUCTURE §3.3). Shared by both catalog tables; the
 * {@code service_addon} repository aliases a SQL {@code NULL} for {@code duration_minutes} (no such
 * column on that table) so this one projection interface serves both queries.
 *
 * <p>The {@code currency} column is {@code CHAR(3)} in PostgreSQL and may carry trailing spaces;
 * callers must {@link String#strip()} it when building a response.
 */
public interface CatalogItemView {

  UUID getId();

  UUID getBusinessId();

  String getName();

  String getDescription();

  long getPriceMinor();

  String getCurrency();

  boolean isActive();

  int getDisplayOrder();

  Integer getDurationMinutes();
}
