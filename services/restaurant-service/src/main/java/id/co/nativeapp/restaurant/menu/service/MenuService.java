package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.MigrateMenuImagesResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.restaurant.recipe.service.RecipeAutoLinkWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates menu read and write operations. Not itself {@code @Transactional} — transactional
 * units of work live in {@link MenuWriter} and {@link MenuReader} so the proxy and the RLS aspect
 * engage (same reasoning as {@code SaleService}).
 */
@Service
public class MenuService {

  private static final Logger log = LoggerFactory.getLogger(MenuService.class);

  private final MenuWriter writer;
  private final MenuReader reader;
  private final RecipeAutoLinkWriter autoLinkWriter;

  public MenuService(MenuWriter writer, MenuReader reader, RecipeAutoLinkWriter autoLinkWriter) {
    this.writer = writer;
    this.reader = reader;
    this.autoLinkWriter = autoLinkWriter;
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
   *
   * <p>With {@code autoTrackStock = true} ("Lacak stok"), the new item is 1:1-auto-linked to a
   * same-named ingredient right after creation — a SECOND transaction on purpose: if the link
   * fails, the item still exists (harmless — the bulk sweep or the drawer 1-klik links it later),
   * never the reverse.
   */
  public MenuItemResponse createItem(CreateMenuItemRequest request) {
    TenantContext.require();
    MenuItemResponse created = writer.create(request);
    if (Boolean.TRUE.equals(request.autoTrackStock())) {
      // BEST-EFFORT (review W1): the item is already committed (its own REQUIRES_NEW tx). A link
      // failure — a name collision with an incompatible ingredient, or a rare concurrent-mint race
      // — must NOT turn a succeeded create into a 500 (there is no menu-item name unique, so the
      // user's retry would DUPLICATE the item). The bulk sweep or the drawer 1-klik links it later.
      try {
        autoLinkWriter.autoLinkItem(created.id());
      } catch (RuntimeException linkFailure) {
        log.warn(
            "auto-track-stock link failed for new menu item {} — item created, left unlinked",
            created.id(),
            linkFailure);
      }
    }
    return created;
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

  /**
   * Converts the CURRENT tenant's remaining legacy inline base64 menu images to the object store
   * (ADR 0048). Owner-triggered, idempotent, RLS-bound — see {@link
   * MenuWriter#migrateLegacyImages()}.
   */
  public MigrateMenuImagesResponse migrateImages() {
    TenantContext.require();
    return writer.migrateLegacyImages();
  }
}
