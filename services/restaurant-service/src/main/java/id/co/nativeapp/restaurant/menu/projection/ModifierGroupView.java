package id.co.nativeapp.restaurant.menu.projection;

import java.util.UUID;

/**
 * Read projection over the {@code menu_item_modifier_group} row.
 *
 * <p>Backs native read queries on {@code ModifierGroupRepository}. Lives in the feature's {@code
 * projection} package.
 */
public interface ModifierGroupView {

  UUID getId();

  UUID getMenuItemId();

  UUID getBusinessId();

  String getName();

  String getSelectionType();

  boolean isRequired();

  int getMinSelect();

  int getMaxSelect();

  int getDisplayOrder();
}
