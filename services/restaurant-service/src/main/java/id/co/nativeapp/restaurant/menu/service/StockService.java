package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates per-item stock management operations. Not itself {@code @Transactional} — the
 * transactional units of work live in {@link StockWriter} so the Spring proxy and the RLS aspect
 * engage on every method (same pattern as {@link MenuService}).
 */
@Service
public class StockService {

  private final StockWriter writer;

  public StockService(StockWriter writer) {
    this.writer = writer;
  }

  /**
   * Sets the absolute stock quantity for a menu item. {@code null} → infinite / untracked.
   * Non-null must be &ge; 0.
   */
  public MenuItemResponse setStock(UUID menuItemId, Integer quantity) {
    TenantContext.require();
    return writer.setStock(menuItemId, quantity);
  }

  /**
   * Adds a signed delta to a tracked item's stock. Throws if the item is untracked (→ 422 at the
   * controller boundary).
   */
  public MenuItemResponse addStock(UUID menuItemId, int amount) {
    TenantContext.require();
    return writer.addStock(menuItemId, amount);
  }
}
