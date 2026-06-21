package id.co.nativeapp.restaurant.menu.projection;

import java.util.UUID;

/**
 * Read projection over the {@code menu_item_modifier_option} row.
 *
 * <p>Backs native read queries on {@code ModifierOptionRepository}. Lives in the feature's {@code
 * projection} package.
 */
public interface ModifierOptionView {

  UUID getId();

  UUID getGroupId();

  UUID getBusinessId();

  String getName();

  long getPriceDeltaMinor();

  boolean isAvailable();

  int getDisplayOrder();
}
