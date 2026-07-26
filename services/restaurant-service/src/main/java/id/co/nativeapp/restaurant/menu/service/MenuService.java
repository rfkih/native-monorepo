package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates menu read and write operations. Not itself {@code @Transactional} — transactional
 * units of work live in {@link MenuWriter} and {@link MenuReader} so the proxy and the RLS aspect
 * engage (same reasoning as {@code SaleService}).
 */
@Service
public class MenuService {

  private final MenuWriter writer;
  private final MenuReader reader;

  public MenuService(MenuWriter writer, MenuReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Returns active menu items for the given business, scoped to the bound tenant by RLS. No
   * explicit {@code company_id} filter in code — RLS handles it (rule 5).
   */
  public List<MenuItemResponse> findActiveByBusiness(UUID businessId) {
    TenantContext.require();
    return reader.findActiveByBusiness(businessId);
  }

  /**
   * Creates a new active menu item. The {@code company_id} is stamped inside {@link
   * MenuWriter#create} from the bound tenant scope, never from the request body (rule 5).
   */
  public MenuItemResponse createItem(CreateMenuItemRequest request) {
    TenantContext.require();
    return writer.create(request);
  }

  /**
   * Soft-deletes a menu item (sets {@code active = false}). The item disappears from {@code GET
   * /api/v1/menu} but historical orders/sales referencing it are unaffected.
   *
   * @param itemId the id of the item to deactivate
   * @throws jakarta.persistence.EntityNotFoundException if not found / not visible to this tenant
   */
  public void deleteItem(UUID itemId) {
    TenantContext.require();
    writer.deactivate(itemId);
  }

  /**
   * Applies a partial update to a menu item. Only non-null fields in {@code request} are applied.
   * The item's currency cannot be changed; {@code priceMinor} updates the amount only.
   *
   * <p>imageUrl PATCH convention: {@code null} = leave unchanged; empty string = clear image; any
   * other value = set/replace image.
   *
   * @param itemId the id of the item to update
   * @param request partial update fields
   * @return the updated item
   * @throws java.util.NoSuchElementException if not found / not visible to this tenant (→ 404)
   */
  public MenuItemResponse updateItem(UUID itemId, UpdateMenuItemRequest request) {
    TenantContext.require();
    return writer.updateItem(itemId, request);
  }
}
