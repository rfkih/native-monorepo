package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.projection.ModifierGroupView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.ModifierGroupRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional unit for modifier groups and options. Kept separate from {@link
 * ModifierWriter} so the Spring proxy and RLS aspect engage.
 *
 * <p>The projection-to-DTO mapping is done here in the service layer (not in the DTO record)
 * because the ArchUnit rule prohibits {@code dto} from depending on {@code projection}; only {@code
 * service} and {@code repository} may access {@code projection} types.
 */
@Component
public class ModifierReader {

  private final ModifierGroupRepository groupRepository;
  private final ModifierOptionRepository optionRepository;

  public ModifierReader(
      ModifierGroupRepository groupRepository, ModifierOptionRepository optionRepository) {
    this.groupRepository = groupRepository;
    this.optionRepository = optionRepository;
  }

  /** Returns all modifier groups for a menu item with their available options embedded. */
  @Transactional(readOnly = true)
  public List<ModifierGroupResponse> findGroupsWithOptions(UUID menuItemId) {
    List<ModifierGroupView> groups = groupRepository.findViewsByMenuItemId(menuItemId);
    return groups.stream()
        .map(
            g -> {
              List<ModifierOptionResponse> options =
                  optionRepository.findAvailableViewsByGroupId(g.getId()).stream()
                      .map(ModifierReader::toOptionResponse)
                      .toList();
              return toGroupResponse(g, options);
            })
        .toList();
  }

  /** Returns all modifier groups for a menu item (admin view — includes unavailable options). */
  @Transactional(readOnly = true)
  public List<ModifierGroupResponse> findGroupsWithAllOptions(UUID menuItemId) {
    List<ModifierGroupView> groups = groupRepository.findViewsByMenuItemId(menuItemId);
    return groups.stream()
        .map(
            g -> {
              List<ModifierOptionResponse> options =
                  optionRepository.findAllViewsByGroupId(g.getId()).stream()
                      .map(ModifierReader::toOptionResponse)
                      .toList();
              return toGroupResponse(g, options);
            })
        .toList();
  }

  /**
   * Maps a {@link ModifierGroupView} projection + options to the response shape. Lives in the
   * service layer so the ArchUnit rule ({@code projection} accessed only by {@code service} and
   * {@code repository}) is respected.
   */
  static ModifierGroupResponse toGroupResponse(
      ModifierGroupView view, List<ModifierOptionResponse> options) {
    return new ModifierGroupResponse(
        view.getId(),
        view.getMenuItemId(),
        view.getBusinessId(),
        view.getName(),
        view.getSelectionType(),
        view.isRequired(),
        view.getMinSelect(),
        view.getMaxSelect(),
        view.getDisplayOrder(),
        options);
  }

  /**
   * Maps a {@link ModifierOptionView} projection to the response shape. Lives in the service layer
   * so the ArchUnit rule ({@code projection} accessed only by {@code service} and {@code
   * repository}) is respected.
   */
  static ModifierOptionResponse toOptionResponse(ModifierOptionView view) {
    return new ModifierOptionResponse(
        view.getId(),
        view.getGroupId(),
        view.getBusinessId(),
        view.getName(),
        view.getPriceDeltaMinor(),
        view.isAvailable(),
        view.getDisplayOrder());
  }
}
