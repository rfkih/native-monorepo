package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the menu feature.
 *
 * <p>A distinct bean from {@link MenuService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code SaleWriter}).
 */
@Component
public class MenuWriter {

  private final MenuItemRepository repository;

  public MenuWriter(MenuItemRepository repository) {
    this.repository = repository;
  }

  /**
   * Creates and persists a new active {@link MenuItem} in its own transaction. The price is
   * validated through {@code libs/money} {@link Money} (ISO-4217; integer minor units, never a
   * float — rule 8). The {@code company_id} is stamped from the bound tenant scope, never from the
   * request body (rule 5).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MenuItemResponse create(CreateMenuItemRequest request) {
    String companyId = TenantContext.require().companyId();

    // Money.ofMinor re-validates the ISO-4217 code; an unknown code throws
    // IllegalArgumentException, which ApiExceptionHandler maps to 400.
    Money price = Money.ofMinor(request.priceMinor(), request.currency());

    MenuItem item =
        new MenuItem(
            request.businessId(),
            request.name(),
            request.category(),
            price,
            request.imageUrl(),
            request.unitCostMinor());
    item.setCompanyId(companyId);
    MenuItem saved = repository.saveAndFlush(item);
    return MenuItemResponse.from(saved);
  }

  /**
   * Applies a partial update ({@code PATCH}) to an existing menu item. Only non-null fields in
   * {@code request} are applied; the item's currency is never changed (PATCH cannot mutate it).
   *
   * <p>imageUrl convention: {@code null} (absent from JSON) = leave unchanged; empty string = clear
   * the image; any other value = set/replace the image.
   *
   * <p>RLS restricts the load to the current tenant — an item from another company is invisible and
   * triggers the same 404 as a genuinely missing item.
   *
   * @param itemId the id of the item to update
   * @param request partial update fields (all nullable)
   * @return the updated item as {@link MenuItemResponse}
   * @throws java.util.NoSuchElementException if the item is not found or not visible to the current
   *     tenant (404 at the controller layer)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MenuItemResponse updateItem(UUID itemId, UpdateMenuItemRequest request) {
    TenantContext.require();

    MenuItem item =
        repository
            .findById(itemId)
            .orElseThrow(
                () -> new java.util.NoSuchElementException("Menu item not found: " + itemId));

    // Resolve price only when priceMinor is provided.
    Money newPrice = null;
    if (request.priceMinor() != null) {
      // Currency is kept from the item — PATCH cannot change the currency (CLAUDE.md rule 8).
      String existingCurrency = item.getPrice().currency().getCurrencyCode();
      newPrice = Money.ofMinor(request.priceMinor(), existingCurrency);
    }

    item.update(
        request.name(), request.category(), newPrice, request.imageUrl(), request.unitCostMinor());
    MenuItem saved = repository.saveAndFlush(item);
    return MenuItemResponse.from(saved);
  }

  /**
   * Soft-deletes a menu item by calling {@link MenuItem#deactivate()}, which sets {@code active =
   * false}. The item immediately disappears from {@code GET /api/v1/menu} (which filters to active
   * items) but existing order/sale rows that reference it are unaffected.
   *
   * <p>RLS restricts the load to the current tenant — an item from another company is invisible and
   * triggers the same 404 as a genuinely missing item. The {@code updated_by} audit column is
   * stamped by {@link id.co.nativeapp.tenant.Auditable Auditable} from the bound actor.
   *
   * @param itemId the id of the menu item to deactivate
   * @throws jakarta.persistence.EntityNotFoundException if the item is not found or not visible to
   *     the current tenant (404 at the controller layer)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deactivate(UUID itemId) {
    TenantContext.require();
    MenuItem item =
        repository
            .findById(itemId)
            .orElseThrow(
                () -> new java.util.NoSuchElementException("Menu item not found: " + itemId));
    item.deactivate();
    repository.saveAndFlush(item);
  }
}
