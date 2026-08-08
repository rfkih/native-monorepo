package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.MigrateMenuImagesResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the menu feature.
 *
 * <p>A distinct bean from {@link MenuService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code SaleWriter}).
 *
 * <p><strong>Convert-on-write (ADR 0048).</strong> An incoming {@code imageUrl} that is an inline
 * base64 data URL (the console's canvas output — the only shape it has ever uploaded) is
 * intercepted here and stored to the object store via {@link MenuImageStore}; the aggregate gains
 * the content-addressed {@code image_key}, never the payload. External HTTP(S) URLs pass through to
 * the legacy column untouched. The API contract is unchanged either way.
 */
@Component
public class MenuWriter {

  private static final Logger log = LoggerFactory.getLogger(MenuWriter.class);

  private final MenuItemRepository repository;
  private final MenuImageStore imageStore;

  public MenuWriter(MenuItemRepository repository, MenuImageStore imageStore) {
    this.repository = repository;
    this.imageStore = imageStore;
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

    boolean inlineImage = MenuImageStore.isDataUrl(request.imageUrl());
    MenuItem item =
        new MenuItem(
            request.businessId(),
            request.name(),
            request.category(),
            price,
            inlineImage ? null : request.imageUrl(),
            request.unitCostMinor());
    item.setCompanyId(companyId);
    if (inlineImage) {
      item.attachImage(imageStore.storeDataUrl(companyId, request.imageUrl()));
    }
    MenuItem saved = repository.saveAndFlush(item);
    return toResponse(saved);
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
    String companyId = TenantContext.require().companyId();

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

    // Convert-on-write (ADR 0048): an inline data URL is stored to the object store and
    // attached as a key; every other imageUrl value keeps the documented PATCH convention
    // (null = unchanged, "" = clear, external URL = legacy passthrough) via update().
    boolean inlineImage = MenuImageStore.isDataUrl(request.imageUrl());
    item.update(
        request.name(),
        request.category(),
        newPrice,
        inlineImage ? null : request.imageUrl(),
        request.unitCostMinor());
    if (inlineImage) {
      item.attachImage(imageStore.storeDataUrl(companyId, request.imageUrl()));
    }
    MenuItem saved = repository.saveAndFlush(item);
    return toResponse(saved);
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

  /**
   * Owner-triggered, per-tenant backfill (ADR 0048): converts every remaining LEGACY inline base64
   * image of the CURRENT tenant to the object store. Idempotent — converted rows no longer match
   * the work-list query, so a re-run finds nothing; and the keys are content-addressed, so a
   * half-completed run that is retried re-puts identical bytes to identical keys.
   *
   * <p>Runs inside a normal tenant-bound transaction (RLS scopes both the work list and every
   * update) — deliberately NOT a Flyway backfill (a migration cannot reach the object store, and
   * under FORCE RLS its UPDATE would silently match 0 rows — the org-service V6/V7 lesson) and NOT
   * a cross-tenant sweep (no tenant enumeration exists, by design).
   *
   * <p>A row whose legacy payload no longer validates (corrupt base64, unrecognised format) is
   * SKIPPED, not fatal: it keeps rendering via dual-read exactly as before, and the count in the
   * response tells the operator it needs manual attention.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MigrateMenuImagesResponse migrateLegacyImages() {
    String companyId = TenantContext.require().companyId();
    List<UUID> legacyIds = repository.findLegacyInlineImageItemIds();
    int migrated = 0;
    int skipped = 0;
    for (UUID itemId : legacyIds) {
      MenuItem item = repository.findById(itemId).orElse(null);
      if (item == null || !MenuImageStore.isDataUrl(item.getImageUrl())) {
        skipped++;
        continue;
      }
      try {
        item.attachImage(imageStore.storeDataUrl(companyId, item.getImageUrl()));
        repository.saveAndFlush(item);
        migrated++;
      } catch (RuntimeException e) {
        // Item id only — never the payload (it stays serving via dual-read).
        log.warn("menu image migrate: skipped item {}: {}", itemId, e.getMessage());
        skipped++;
      }
    }
    return new MigrateMenuImagesResponse(migrated, skipped);
  }

  /** Write-path response mapping with the image key resolved to its public URL (ADR 0048). */
  private MenuItemResponse toResponse(MenuItem item) {
    return MenuItemResponse.from(item, resolvedImageUrl(item));
  }

  /** The resolved public/legacy image URL for a write-path aggregate. */
  @Nullable String resolvedImageUrl(MenuItem item) {
    return imageStore.resolve(item.getImageKey(), item.getImageUrl());
  }
}
