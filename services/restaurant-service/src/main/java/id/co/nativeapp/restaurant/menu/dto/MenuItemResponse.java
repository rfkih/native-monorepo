package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import java.util.UUID;

/** Response body for a single menu item. */
public record MenuItemResponse(
    UUID id,
    UUID businessId,
    String name,
    String category,
    long priceMinor,
    String currency,
    boolean active) {

  /** Maps the write-path aggregate to the response shape. */
  public static MenuItemResponse from(MenuItem item) {
    return new MenuItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getCategory(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive());
  }
}
