package id.co.nativeapp.restaurant.menu.projection;

import java.util.UUID;

/**
 * Read projection over the {@code menu_category} row — only the columns a response needs.
 *
 * <p>Backs the native read queries on {@code MenuCategoryRepository}. Lives in the feature's {@code
 * projection} package (accessed only by service + repository per the ArchUnit rule).
 */
public interface MenuCategoryView {

  UUID getId();

  UUID getBusinessId();

  String getName();

  int getDisplayOrder();

  boolean isActive();
}
