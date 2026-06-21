package id.co.nativeapp.restaurant.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * A single line in the {@link CheckoutRequest}: which menu item, how many, and which modifier
 * options were selected.
 *
 * @param menuItemId the menu item to add
 * @param qty quantity; must be &ge; 1
 * @param selectedOptionIds optional list of modifier option ids the customer selected; may be
 *     {@code null} or empty when the item has no modifiers or the caller omits them. The service
 *     validates each option exists, belongs to a group of THIS menu item, and is available.
 */
public record OrderLineRequest(
    @NotNull UUID menuItemId, @Min(1) int qty, List<UUID> selectedOptionIds) {

  /**
   * Compatibility constructor with no selected options — preserves existing two-arg call sites
   * (menuItemId + qty) without breaking them.
   */
  public OrderLineRequest(UUID menuItemId, int qty) {
    this(menuItemId, qty, List.of());
  }

  /** Returns the selected option ids, or an empty list when none were chosen. */
  public List<UUID> selectedOptionIds() {
    return selectedOptionIds == null ? List.of() : selectedOptionIds;
  }
}
