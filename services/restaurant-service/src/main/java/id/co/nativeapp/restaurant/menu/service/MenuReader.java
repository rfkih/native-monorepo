package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.projection.ModifierGroupView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierGroupRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private static final int CHUNK_SIZE = 1000;

  private final MenuItemRepository repository;
  private final ModifierGroupRepository groupRepository;
  private final ModifierOptionRepository optionRepository;

  public MenuReader(
      MenuItemRepository repository,
      ModifierGroupRepository groupRepository,
      ModifierOptionRepository optionRepository) {
    this.repository = repository;
    this.groupRepository = groupRepository;
    this.optionRepository = optionRepository;
  }

  /**
   * Active menu items for a business, projected to the response shape, with each item's active
   * modifier groups and available options embedded. No {@code WHERE company_id} — RLS auto-applies
   * (rule 5).
   *
   * <p>No N+1: modifier groups and options are batch-loaded in at most two additional native-query
   * projection reads (chunked at {@value CHUNK_SIZE} ids per IN clause), then assembled into a
   * {@code Map<itemId, List<ModifierGroupResponse>>} in the service layer before being attached to
   * each {@link MenuItemResponse}.
   */
  @Transactional(readOnly = true)
  public List<MenuItemResponse> findActiveByBusiness(UUID businessId) {
    List<MenuItemView> items = repository.findActiveByBusiness(businessId);
    if (items.isEmpty()) {
      return List.of();
    }

    // --- batch-load groups (query 1 of 2) ---
    List<UUID> itemIds = items.stream().map(MenuItemView::getId).toList();
    List<ModifierGroupView> allGroups = batchLoadGroups(itemIds);

    // --- batch-load available options (query 2 of 2) ---
    Map<UUID, List<ModifierGroupView>> groupsByItemId = new HashMap<>();
    for (ModifierGroupView g : allGroups) {
      groupsByItemId.computeIfAbsent(g.getMenuItemId(), k -> new ArrayList<>()).add(g);
    }

    List<UUID> groupIds = allGroups.stream().map(ModifierGroupView::getId).toList();
    Map<UUID, List<ModifierOptionView>> optionsByGroupId = new HashMap<>();
    if (!groupIds.isEmpty()) {
      List<ModifierOptionView> allOptions = batchLoadOptions(groupIds);
      for (ModifierOptionView o : allOptions) {
        optionsByGroupId.computeIfAbsent(o.getGroupId(), k -> new ArrayList<>()).add(o);
      }
    }

    // --- assemble responses ---
    return items.stream()
        .map(
            item -> {
              List<ModifierGroupView> groups = groupsByItemId.getOrDefault(item.getId(), List.of());
              List<ModifierGroupResponse> groupResponses =
                  groups.stream()
                      .map(
                          g ->
                              ModifierReader.toGroupResponse(
                                  g,
                                  optionsByGroupId.getOrDefault(g.getId(), List.of()).stream()
                                      .map(ModifierReader::toOptionResponse)
                                      .toList()))
                      .toList();
              return toResponse(item, groupResponses);
            })
        .toList();
  }

  private List<ModifierGroupView> batchLoadGroups(List<UUID> itemIds) {
    List<ModifierGroupView> result = new ArrayList<>();
    for (int i = 0; i < itemIds.size(); i += CHUNK_SIZE) {
      List<UUID> chunk = itemIds.subList(i, Math.min(i + CHUNK_SIZE, itemIds.size()));
      result.addAll(groupRepository.findActiveViewsByMenuItemIds(chunk));
    }
    return result;
  }

  private List<ModifierOptionView> batchLoadOptions(List<UUID> groupIds) {
    List<ModifierOptionView> result = new ArrayList<>();
    for (int i = 0; i < groupIds.size(); i += CHUNK_SIZE) {
      List<UUID> chunk = groupIds.subList(i, Math.min(i + CHUNK_SIZE, groupIds.size()));
      result.addAll(optionRepository.findAvailableViewsByGroupIds(chunk));
    }
    return result;
  }

  /**
   * Maps a read-path projection to the response shape. Currency is {@code CHAR(3)} (PostgreSQL
   * right-pads it) so strip before returning. Lives in the service layer so the ArchUnit rule
   * ({@code projection} accessed only by {@code service} and {@code repository}) is respected.
   *
   * <p>Phase 3: includes {@code categoryId}, {@code available}, and the item's active modifier
   * groups with their available options (populated on the cashier read path via a batch load — see
   * {@link #findActiveByBusiness}).
   *
   * <p>Stock increment: includes {@code stockQuantity} ({@code null} = untracked / infinite).
   *
   * <p>Image increment: includes {@code imageUrl} ({@code null} = no image set).
   */
  static MenuItemResponse toResponse(MenuItemView view, List<ModifierGroupResponse> groups) {
    return new MenuItemResponse(
        view.getId(),
        view.getBusinessId(),
        view.getName(),
        view.getCategory(),
        view.getCategoryId(),
        view.getPriceMinor(),
        view.getCurrency().strip(),
        view.isActive(),
        view.isAvailable(),
        view.getStockQuantity(),
        view.getImageUrl(),
        view.getUnitCostMinor(),
        groups);
  }
}
