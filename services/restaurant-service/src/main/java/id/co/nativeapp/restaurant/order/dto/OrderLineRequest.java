package id.co.nativeapp.restaurant.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A single line in the {@link CheckoutRequest}: which menu item and how many.
 *
 * @param menuItemId the menu item to add
 * @param qty quantity; must be &ge; 1
 */
public record OrderLineRequest(@NotNull UUID menuItemId, @Min(1) int qty) {}
