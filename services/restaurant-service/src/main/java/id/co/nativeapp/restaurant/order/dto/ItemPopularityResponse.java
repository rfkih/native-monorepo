package id.co.nativeapp.restaurant.order.dto;

import java.util.UUID;

/**
 * One row of the menu-item popularity ranking: how many units of the item have ever been sold
 * (paid) at the business — completed walk-in order lines plus paid bill lines. The POS sorts its
 * "All" tab best-sellers-first with this; a count, not money, so no currency travels here.
 *
 * @param menuItemId the menu item
 * @param soldQty total units sold across completed orders and paid bill lines
 */
public record ItemPopularityResponse(UUID menuItemId, long soldQty) {}
