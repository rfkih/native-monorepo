package id.co.nativeapp.restaurant.order.projection;

import java.util.UUID;

/**
 * Read projection for the menu-item popularity ranking: how many units of each menu item have
 * ever been SOLD (paid) at a business — completed walk-in order lines plus paid bill lines. Backs
 * the native union query on {@code OrderLineRepository}; the POS uses it to sort the "All" tab
 * best-sellers-first.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface ItemPopularityView {

  UUID getMenuItemId();

  long getSoldQty();
}
