package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
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

    MenuItem item = new MenuItem(request.businessId(), request.name(), request.category(), price);
    item.setCompanyId(companyId);
    MenuItem saved = repository.saveAndFlush(item);
    return MenuItemResponse.from(saved);
  }
}
