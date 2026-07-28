package id.co.nativeapp.restaurant.menu.domain;

import java.util.UUID;

/**
 * Thrown when a checkout attempts to deduct more stock than is available for a tracked menu item.
 *
 * <p>Non-retryable: the caller should surface this as a {@code 422 Unprocessable Entity} and roll
 * back the enclosing transaction. The item id is included so the exception handler can name the
 * offending item without logging any PII.
 */
public class InsufficientStockException extends RuntimeException {

  private final UUID menuItemId;
  private final String itemName;
  private final int requested;
  private final int available;

  public InsufficientStockException(
      UUID menuItemId, String itemName, int requested, int available) {
    super(
        "Insufficient stock for item '"
            + itemName
            + "' ("
            + menuItemId
            + "): requested "
            + requested
            + ", available "
            + available);
    this.menuItemId = menuItemId;
    this.itemName = itemName;
    this.requested = requested;
    this.available = available;
  }

  public UUID getMenuItemId() {
    return menuItemId;
  }

  public String getItemName() {
    return itemName;
  }

  public int getRequested() {
    return requested;
  }

  public int getAvailable() {
    return available;
  }
}
