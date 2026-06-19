package id.co.nativeapp.restaurant.menu.projection;

import java.util.UUID;

/**
 * Read projection over the {@code menu_item} row — only the columns a response needs, never the
 * {@link id.co.nativeapp.tenant.Auditable Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code MenuItemRepository} (e.g. {@code
 * findActiveByBusiness}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"). Lives in its own {@code projection} package — a read model is neither the
 * write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface MenuItemView {

  UUID getId();

  UUID getBusinessId();

  String getName();

  String getCategory();

  long getPriceMinor();

  String getCurrency();

  boolean isActive();
}
