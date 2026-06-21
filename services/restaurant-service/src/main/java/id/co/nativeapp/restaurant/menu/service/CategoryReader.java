package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.CategoryResponse;
import id.co.nativeapp.restaurant.menu.projection.MenuCategoryView;
import id.co.nativeapp.restaurant.menu.repository.MenuCategoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional unit for the category feature. Kept as a separate bean from {@link
 * CategoryWriter} following the {@code *Writer} / {@code *Reader} split.
 *
 * <p>The projection-to-DTO mapping is done here in the service layer (not in the DTO record)
 * because the ArchUnit rule prohibits {@code dto} from depending on {@code projection}; only {@code
 * service} and {@code repository} may access {@code projection} types.
 */
@Component
public class CategoryReader {

  private final MenuCategoryRepository repository;

  public CategoryReader(MenuCategoryRepository repository) {
    this.repository = repository;
  }

  /** Returns all categories for a business, ordered by display_order then name. */
  @Transactional(readOnly = true)
  public List<CategoryResponse> findAllByBusiness(UUID businessId) {
    return repository.findAllByBusiness(businessId).stream()
        .map(CategoryReader::toResponse)
        .toList();
  }

  /**
   * Maps a read-path {@link MenuCategoryView} to the response shape. Lives in the service layer so
   * the ArchUnit rule ({@code projection} accessed only by {@code service} and {@code repository})
   * is respected.
   */
  static CategoryResponse toResponse(MenuCategoryView view) {
    return new CategoryResponse(
        view.getId(),
        view.getBusinessId(),
        view.getName(),
        view.getDisplayOrder(),
        view.isActive());
  }
}
