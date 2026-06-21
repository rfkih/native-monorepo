package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.domain.MenuCategory;
import id.co.nativeapp.restaurant.menu.dto.CategoryResponse;
import id.co.nativeapp.restaurant.menu.dto.CreateCategoryRequest;
import id.co.nativeapp.restaurant.menu.dto.ReorderCategoryRequest;
import id.co.nativeapp.restaurant.menu.repository.MenuCategoryRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the category feature.
 *
 * <p>A distinct bean from {@link CategoryService} so each transactional method is invoked through
 * the Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code MenuWriter}).
 */
@Component
public class CategoryWriter {

  private final MenuCategoryRepository repository;

  public CategoryWriter(MenuCategoryRepository repository) {
    this.repository = repository;
  }

  /** Creates and persists a new active {@link MenuCategory} in its own transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CategoryResponse create(CreateCategoryRequest request) {
    String companyId = TenantContext.require().companyId();
    MenuCategory category =
        new MenuCategory(request.businessId(), request.name(), request.displayOrder());
    category.setCompanyId(companyId);
    MenuCategory saved = repository.saveAndFlush(category);
    return CategoryResponse.from(saved);
  }

  /** Reorders a category by updating its {@code display_order}. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CategoryResponse reorder(UUID categoryId, ReorderCategoryRequest request) {
    TenantContext.require();
    MenuCategory category =
        repository
            .findById(categoryId)
            .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
    category.reorder(request.displayOrder());
    MenuCategory saved = repository.saveAndFlush(category);
    return CategoryResponse.from(saved);
  }

  /** Activates a category — makes its items visible in the cashier view. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CategoryResponse activate(UUID categoryId) {
    TenantContext.require();
    MenuCategory category =
        repository
            .findById(categoryId)
            .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
    category.activate();
    MenuCategory saved = repository.saveAndFlush(category);
    return CategoryResponse.from(saved);
  }

  /** Deactivates a category — hides its items from the cashier view. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CategoryResponse deactivate(UUID categoryId) {
    TenantContext.require();
    MenuCategory category =
        repository
            .findById(categoryId)
            .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
    category.deactivate();
    MenuCategory saved = repository.saveAndFlush(category);
    return CategoryResponse.from(saved);
  }
}
