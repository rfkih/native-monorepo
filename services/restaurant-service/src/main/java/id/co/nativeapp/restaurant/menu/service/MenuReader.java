package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional unit for the menu feature. Kept as a separate bean from {@link
 * MenuWriter} following the {@code *Writer} / {@code *Reader} split so the Spring proxy and the RLS
 * aspect engage on every method.
 *
 * <p>The projection-to-DTO mapping is done here in the service layer (not in the DTO record)
 * because the ArchUnit rule prohibits {@code dto} from depending on {@code projection}; only {@code
 * service} and {@code repository} may access {@code projection} types.
 */
@Component
public class MenuReader {

  private final MenuItemRepository repository;

  public MenuReader(MenuItemRepository repository) {
    this.repository = repository;
  }

  /**
   * Active menu items for a business, projected to the response shape. No {@code WHERE company_id}
   * — RLS auto-applies (rule 5).
   */
  @Transactional(readOnly = true)
  public List<MenuItemResponse> findActiveByBusiness(UUID businessId) {
    return repository.findActiveByBusiness(businessId).stream()
        .map(MenuReader::toResponse)
        .toList();
  }

  /**
   * Maps a read-path projection to the response shape. Currency is {@code CHAR(3)} (PostgreSQL
   * right-pads it) so strip before returning. Lives in the service layer so the ArchUnit rule
   * ({@code projection} accessed only by {@code service} and {@code repository}) is respected.
   */
  static MenuItemResponse toResponse(MenuItemView view) {
    return new MenuItemResponse(
        view.getId(),
        view.getBusinessId(),
        view.getName(),
        view.getCategory(),
        view.getPriceMinor(),
        view.getCurrency().strip(),
        view.isActive());
  }
}
