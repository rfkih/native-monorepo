package id.co.nativeapp.restaurant.menu.domain;

import java.util.UUID;

/**
 * Thrown when a stock-delta operation ({@code addStock}) is called on an untracked (infinite) menu
 * item — one whose {@code stock_quantity} is {@code NULL}.
 *
 * <p>Surfaces as {@code 422 Unprocessable Entity}: the caller must first set an absolute stock
 * quantity via {@code PUT /api/v1/menu/{itemId}/stock} before adding a delta.
 */
public class UntrackedStockException extends RuntimeException {

  private final UUID menuItemId;

  public UntrackedStockException(UUID menuItemId) {
    super(
        "Menu item "
            + menuItemId
            + " is untracked (infinite stock). Set a stock quantity first via"
            + " PUT /api/v1/menu/{itemId}/stock before adding a delta.");
    this.menuItemId = menuItemId;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }
}
