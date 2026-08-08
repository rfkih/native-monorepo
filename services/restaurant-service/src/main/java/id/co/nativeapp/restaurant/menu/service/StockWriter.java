package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.domain.UntrackedStockException;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for per-item stock management.
 *
 * <p>A distinct bean from {@link StockService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code MenuWriter}).
 */
@Component
public class StockWriter {

  private final MenuItemRepository repository;
  private final MenuImageStore imageStore;

  public StockWriter(MenuItemRepository repository, MenuImageStore imageStore) {
    this.repository = repository;
    this.imageStore = imageStore;
  }

  /**
   * Sets the absolute stock quantity for a menu item. Passing {@code null} reverts it to infinite /
   * untracked. A non-null value must be &ge; 0 (enforced by {@link MenuItem#setStock(Integer)}).
   *
   * <p>Runs in its own {@code REQUIRES_NEW} transaction so the RLS aspect engages (tenant GUC is
   * set on entry). The full aggregate is loaded (write path) so that the dirty-checking flush
   * propagates the change atomically.
   *
   * @param menuItemId the item to update (must be visible under the current tenant)
   * @param quantity new absolute stock (&ge; 0), or {@code null} to make it infinite/untracked
   * @return the updated response
   * @throws NoSuchElementException if the item is not found (→ 404 / 400 via handler)
   * @throws IllegalArgumentException if quantity is negative
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MenuItemResponse setStock(UUID menuItemId, Integer quantity) {
    TenantContext.require();
    MenuItem item =
        repository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    item.setStock(quantity);
    MenuItem saved = repository.saveAndFlush(item);
    return MenuItemResponse.from(
        saved, imageStore.resolve(saved.getImageKey(), saved.getImageUrl()));
  }

  /**
   * Adds a signed delta to a tracked item's stock, flooring at 0. Throws {@link
   * UntrackedStockException} (→ 422) if the item has no tracked stock yet.
   *
   * @param menuItemId the item to update (must be visible under the current tenant)
   * @param amount signed delta to add
   * @return the updated response
   * @throws NoSuchElementException if the item is not found
   * @throws UntrackedStockException if the item is untracked (infinite stock)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MenuItemResponse addStock(UUID menuItemId, int amount) {
    TenantContext.require();
    MenuItem item =
        repository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    item.addStock(amount); // throws UntrackedStockException if not tracked
    MenuItem saved = repository.saveAndFlush(item);
    return MenuItemResponse.from(
        saved, imageStore.resolve(saved.getImageKey(), saved.getImageUrl()));
  }
}
