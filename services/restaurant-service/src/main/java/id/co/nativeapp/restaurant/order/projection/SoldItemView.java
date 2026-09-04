package id.co.nativeapp.restaurant.order.projection;

import java.util.UUID;

/**
 * Read projection for the per-menu-item sales breakdown over a time WINDOW — units sold and gross
 * revenue per item across completed walk-in order lines and paid bill lines whose sale occurred in
 * {@code [from, to)}. Backs the windowed union query on {@code OrderLineRepository}; the Z-report
 * (register-close summary) and the stock-opname reference render "Burger ×12 · Rp 300.000" from it.
 *
 * <p>{@code name} is the line's {@code name_snapshot} (per {@code MAX} across the window), so a
 * renamed/deleted menu item still shows its sold-time name with no join to {@code menu}. Revenue is
 * the sum of line totals in minor units (rule 8) — GROSS per item (order-level discount/tax stay in
 * the summary totals), so the item list need not foot exactly to the summary's net.
 *
 * <p>Lives in its own {@code projection} package — a read model, not the write-side entity nor a
 * DTO.
 */
public interface SoldItemView {

  UUID getMenuItemId();

  String getName();

  long getSoldQty();

  long getRevenueMinor();
}
