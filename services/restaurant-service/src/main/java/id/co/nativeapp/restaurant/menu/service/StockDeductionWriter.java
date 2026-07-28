package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically deducts stock for each line in a finalized sale, joining the caller's existing
 * transaction (propagation {@code MANDATORY}).
 *
 * <p>This writer is invoked by {@link id.co.nativeapp.restaurant.order.service.OrderWriter
 * OrderWriter} for the cash/no-payment checkout path and the {@code payParked} path — both of which
 * record a {@code SaleRecorded} outbox event. Because propagation is {@code MANDATORY}, the UPDATE
 * runs on the same physical connection as the order + sale + outbox writes, so a deduction failure
 * rolls back everything (no partial sale, no phantom SaleRecorded).
 *
 * <p><strong>Concurrency safety:</strong> the underlying {@code MenuItemRepository#deductStock}
 * UPDATE is gated on {@code stock_quantity IS NOT NULL AND stock_quantity >= qty}. If the row is
 * locked by a concurrent checkout the UPDATE waits and then either succeeds (stock was sufficient)
 * or returns 0 rows (stock was exhausted). A 0-row result on a tracked item causes {@link
 * InsufficientStockException} to be thrown, which rolls back the enclosing transaction — no
 * oversell.
 *
 * <p>Untracked items ({@code stock_quantity IS NULL}) are silently skipped: the UPDATE WHERE clause
 * excludes them (0 rows updated is the correct outcome, and the caller treats 0 rows as "untracked"
 * when the item's {@code stockQuantity} view field is null).
 */
@Component
public class StockDeductionWriter {

  private final MenuItemRepository menuItemRepository;

  public StockDeductionWriter(MenuItemRepository menuItemRepository) {
    this.menuItemRepository = menuItemRepository;
  }

  /**
   * Deducts stock for each line in the sale. Must be called inside an active transaction
   * (propagation {@code MANDATORY}).
   *
   * <p>For each line whose menu item is tracked ({@code stock_quantity IS NOT NULL}):
   *
   * <ol>
   *   <li>Issues the atomic {@code UPDATE menu_item SET stock_quantity = stock_quantity - :qty ...
   *       WHERE stock_quantity IS NOT NULL AND stock_quantity >= :qty}.
   *   <li>If the UPDATE returns 0 rows, loads a fresh view of the item to determine whether it is
   *       tracked (null view.stockQuantity = untracked, so 0 rows is expected and the loop
   *       continues) or whether stock is genuinely insufficient (tracked but 0 rows = race lost →
   *       throw {@link InsufficientStockException}).
   * </ol>
   *
   * <p>Untracked items produce a 0-row UPDATE that is immediately classified as "untracked → no-op"
   * by checking {@code view.getStockQuantity() == null}, so they are never blocked.
   *
   * @param lines the order lines; each line contributes its {@code menuItemId} and {@code qty}
   * @param itemViews pre-loaded projections from the checkout validation step, used to check
   *     tracked/untracked status before issuing the UPDATE
   * @throws InsufficientStockException if any tracked item has insufficient stock
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void deductForLines(List<OrderLine> lines, List<MenuItemView> itemViews) {
    if (lines.isEmpty()) {
      return;
    }

    // Build a map from menuItemId → view for quick tracked-status lookup.
    Map<UUID, MenuItemView> viewMap =
        itemViews.stream().collect(Collectors.toMap(MenuItemView::getId, Function.identity()));

    // Aggregate quantities per menu item (a single order might have the same item on multiple
    // lines, though the UI typically prevents it; we handle it correctly anyway).
    Map<UUID, Integer> qtyByItem =
        lines.stream()
            .collect(Collectors.toMap(OrderLine::getMenuItemId, OrderLine::getQty, Integer::sum));

    List<UUID> trackedIds = new ArrayList<>();
    for (Map.Entry<UUID, Integer> entry : qtyByItem.entrySet()) {
      UUID itemId = entry.getKey();
      MenuItemView view = viewMap.get(itemId);
      if (view != null && view.getStockQuantity() != null) {
        // Only issue the UPDATE for items we know are tracked at checkout time.
        trackedIds.add(itemId);
      }
      // Untracked items are skipped — no UPDATE needed.
    }

    for (UUID itemId : trackedIds) {
      int qty = qtyByItem.get(itemId);
      int updated = menuItemRepository.deductStock(itemId, qty);
      if (updated == 0) {
        // The UPDATE returned 0 rows. Since we pre-checked that the item was tracked, this means
        // a concurrent checkout exhausted the stock between our read and this UPDATE — insufficient
        // stock. Throw to roll back the entire transaction.
        MenuItemView view = viewMap.get(itemId);
        String itemName = view != null ? view.getName() : itemId.toString();
        // We don't know the exact remaining stock (another tx changed it), so report 0.
        throw new InsufficientStockException(itemId, itemName, qty, 0);
      }
    }
  }
}
