package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.MDC;
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
 *
 * <p><strong>Phase 5 (ADR 0028) offline-replay policy.</strong> {@link
 * #deductForLinesAllowingNegative} is the offline-replay counterpart to {@link #deductForLines}: it
 * NEVER throws {@link InsufficientStockException} — the cash is already in the drawer, so the sale
 * is recorded regardless — and instead deducts the tracked item's stock allowing the level to go
 * negative, recording a discrepancy to the error-log inbox ({@code libs/error-inbox}, ADR 0005/0009)
 * for repair by a physical count. {@link #deductForLines} (the online path) is unchanged.
 */
@Component
public class StockDeductionWriter {

  /** The error-inbox {@code source} label for an offline-replay stock discrepancy. */
  static final String OFFLINE_DISCREPANCY_SOURCE = "checkout:offline-stock-discrepancy";

  private final MenuItemRepository menuItemRepository;
  private final ErrorInboxWriter errorInboxWriter;

  public StockDeductionWriter(
      MenuItemRepository menuItemRepository, ErrorInboxWriter errorInboxWriter) {
    this.menuItemRepository = menuItemRepository;
    this.errorInboxWriter = errorInboxWriter;
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

  /**
   * Phase 5 (ADR 0028): deducts stock for a replayed offline sale — NEVER throws {@link
   * InsufficientStockException}. For each tracked item, the deduction always applies (the level is
   * allowed to go negative); when the pre-loaded {@code itemViews} snapshot shows fewer units were
   * available than requested, a discrepancy is recorded to the error-log inbox with enough context
   * (order id, item id/name, requested vs. the snapshot-known available) for repair by a physical
   * count. Untracked items are skipped, exactly like {@link #deductForLines}.
   *
   * <p>The "available" figure is the item's {@code stock_quantity} AS OBSERVED when the cart was
   * built (before this deduction), not a race-exact read at deduction time — precise enough for a
   * reconciliation report; the money is what matters here, not a perfectly race-free stock count.
   *
   * @param lines the order lines; each line contributes its {@code menuItemId} and {@code qty}
   * @param itemViews pre-loaded projections from the checkout validation step
   * @param orderId the order id, stamped on the discrepancy record for traceability
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void deductForLinesAllowingNegative(
      List<OrderLine> lines, List<MenuItemView> itemViews, UUID orderId) {
    if (lines.isEmpty()) {
      return;
    }

    Map<UUID, MenuItemView> viewMap =
        itemViews.stream().collect(Collectors.toMap(MenuItemView::getId, Function.identity()));
    Map<UUID, Integer> qtyByItem =
        lines.stream()
            .collect(Collectors.toMap(OrderLine::getMenuItemId, OrderLine::getQty, Integer::sum));

    for (Map.Entry<UUID, Integer> entry : qtyByItem.entrySet()) {
      UUID itemId = entry.getKey();
      int qty = entry.getValue();
      MenuItemView view = viewMap.get(itemId);
      if (view == null || view.getStockQuantity() == null) {
        // Untracked (or unknown) — no UPDATE needed, never a discrepancy.
        continue;
      }
      int knownAvailable = view.getStockQuantity();
      menuItemRepository.forceDeductStock(itemId, qty);
      if (knownAvailable < qty) {
        recordDiscrepancy(orderId, itemId, view.getName(), qty, knownAvailable);
      }
    }
  }

  /**
   * Records an offline-replay stock discrepancy to the error-log inbox (ADR 0005/0009) IN THE
   * CALLER'S TRANSACTION ({@link ErrorInboxWriter#recordInCurrentTx}): the discrepancy documents
   * this checkout's own stock side effect, so if the checkout rolls back after the deduction (a
   * later payment/sale failure) the record must roll back with it — a surviving row would be a
   * phantom "oversold" alert for a sale that never happened (review W-2). The writer still
   * swallows its own failures — ops infrastructure must never break the checkout it observes.
   */
  private void recordDiscrepancy(
      UUID orderId, UUID menuItemId, String itemName, int requested, int available) {
    String companyId = TenantContext.require().companyId();
    String traceId = MDC.get("traceId");
    String message =
        "Offline-replay checkout oversold tracked stock: order="
            + orderId
            + " menuItem="
            + menuItemId
            + " ('"
            + itemName
            + "') requested="
            + requested
            + " availableAtCartBuild="
            + available
            + " — stock allowed negative; repair by physical count.";
    errorInboxWriter.recordInCurrentTx(
        new IllegalStateException(message), OFFLINE_DISCREPANCY_SOURCE, companyId, traceId);
  }
}
