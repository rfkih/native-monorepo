package id.co.nativeapp.restaurant.order.dto;

import java.util.UUID;

/**
 * One row of the per-menu-item sales breakdown over a time window — units sold and GROSS revenue
 * (minor units) for the item across completed walk-in orders and paid bill lines whose sale
 * occurred in the requested {@code [from, to)}. Powers the Z-report items section and the
 * stock-opname "sold today" reference.
 *
 * <p>No currency travels here (like {@link ItemPopularityResponse}): the caller already holds the
 * outlet's currency (the summary's, or the session base currency) and formats {@code revenueMinor}
 * with it. {@code name} is the sold-time {@code name_snapshot}.
 *
 * @param menuItemId the menu item
 * @param name the item's sold-time name snapshot
 * @param soldQty total units sold across completed orders and paid bill lines in the window
 * @param revenueMinor gross revenue for the item in minor units (sum of line totals)
 */
public record ItemSalesResponse(UUID menuItemId, String name, long soldQty, long revenueMinor) {}
