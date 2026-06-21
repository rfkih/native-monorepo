package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.CategoryResponse;
import id.co.nativeapp.restaurant.menu.dto.CreateCategoryRequest;
import id.co.nativeapp.restaurant.menu.dto.ReorderCategoryRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates category read and write operations.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link CategoryWriter} and
 * {@link CategoryReader} so the proxy and the RLS aspect engage (same reasoning as {@code
 * MenuService}).
 */
@Service
public class CategoryService {

  private final CategoryWriter writer;
  private final CategoryReader reader;

  public CategoryService(CategoryWriter writer, CategoryReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Returns all categories for the given business, ordered by display_order then name. RLS-scoped.
   */
  public List<CategoryResponse> findAllByBusiness(UUID businessId) {
    TenantContext.require();
    return reader.findAllByBusiness(businessId);
  }

  /** Creates a new active category. The {@code company_id} comes from the bound tenant scope. */
  public CategoryResponse createCategory(CreateCategoryRequest request) {
    TenantContext.require();
    return writer.create(request);
  }

  /** Reorders a category by setting a new {@code display_order}. */
  public CategoryResponse reorder(UUID categoryId, ReorderCategoryRequest request) {
    TenantContext.require();
    return writer.reorder(categoryId, request);
  }

  /** Activates a category (makes it and its items visible in the cashier view). */
  public CategoryResponse activate(UUID categoryId) {
    TenantContext.require();
    return writer.activate(categoryId);
  }

  /** Deactivates a category (hides it and its items from the cashier view). */
  public CategoryResponse deactivate(UUID categoryId) {
    TenantContext.require();
    return writer.deactivate(categoryId);
  }
}
