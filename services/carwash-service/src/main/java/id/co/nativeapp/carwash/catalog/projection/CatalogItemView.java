package id.co.nativeapp.carwash.catalog.projection;

import java.util.UUID;

/**
 * Read projection over a {@code wash_package} or {@code wash_addon} row — only the columns a {@link
 * id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse CatalogItemResponse} needs, never the
 * {@code Auditable} bookkeeping (CODE-STRUCTURE §3.3). Shared by both catalog tables (identical
 * column shape).
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
}
