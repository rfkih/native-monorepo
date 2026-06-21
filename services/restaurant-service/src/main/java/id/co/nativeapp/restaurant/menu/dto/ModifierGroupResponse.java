package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.ModifierGroup;
import java.util.List;
import java.util.UUID;

/**
 * Response body for a modifier group (optionally with its options embedded).
 *
 * @param id the group id
 * @param menuItemId the owning menu item
 * @param businessId the owning business unit
 * @param name the group name
 * @param selectionType {@code SINGLE} or {@code MULTI}
 * @param required whether this group requires a selection
 * @param minSelect minimum selections required
 * @param maxSelect maximum selections allowed
 * @param displayOrder sort position
 * @param options the options in this group (may be empty if not loaded)
 */
public record ModifierGroupResponse(
    UUID id,
    UUID menuItemId,
    UUID businessId,
    String name,
    String selectionType,
    boolean required,
    int minSelect,
    int maxSelect,
    int displayOrder,
    List<ModifierOptionResponse> options) {

  /** Maps from the write-path aggregate (no options). */
  public static ModifierGroupResponse from(ModifierGroup group) {
    return new ModifierGroupResponse(
        group.getId(),
        group.getMenuItemId(),
        group.getBusinessId(),
        group.getName(),
        group.getSelectionType(),
        group.isRequired(),
        group.getMinSelect(),
        group.getMaxSelect(),
        group.getDisplayOrder(),
        List.of());
  }
  // NOTE: from(ModifierGroupView) and from(ModifierGroupView, List) live in ModifierReader
  // (service layer) — not here — because the ArchUnit rule prohibits dto from depending
  // on projection.
}
