package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.restaurant.menu.projection.ModifierGroupView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.ModifierGroupRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared modifier validation + pricing helper used by BOTH {@link OrderWriter} (checkout) and
 * {@link OrderReader} (quote) so they cannot diverge.
 *
 * <p>For each order line this component:
 *
 * <ol>
 *   <li>De-duplicates {@code selectedOptionIds} (duplicate ids cause double-charging — rejected
 *       with a 400 rather than silently de-duped, to surface client bugs).
 *   <li>Validates every selected option exists (RLS-scoped), is available, and belongs to a group
 *       of <em>this</em> menu item.
 *   <li>Loads <strong>ALL</strong> modifier groups for the item — not just required ones — and
 *       enforces {@code min_select}/{@code max_select} uniformly across all groups.
 *   <li>Enforces {@code SINGLE} selection-type ({@code max_select = 1} effective).
 *   <li>Computes {@code effectiveUnit = unitPriceMinor + Σ priceDeltaMinor} and rejects a negative
 *       result (would invert the price of the item).
 * </ol>
 *
 * <p>All methods are stateless; the bean carries only repository references. Callers pre-load the
 * option and group maps (via chunked IN queries) and pass them in to avoid N+1 queries.
 */
@Component
public class ModifierValidationReader {

  private final ModifierGroupRepository groupRepository;
  private final ModifierOptionRepository optionRepository;

  public ModifierValidationReader(
      ModifierGroupRepository groupRepository, ModifierOptionRepository optionRepository) {
    this.groupRepository = groupRepository;
    this.optionRepository = optionRepository;
  }

  /**
   * Loads all options and groups needed to validate the full list of lines.
   *
   * @param lines the order lines (may have empty selectedOptionIds)
   * @return a {@link ValidationContext} carrying the pre-loaded maps; pass to {@link #validateLine}
   */
  public ValidationContext loadContext(List<OrderLineRequest> lines) {
    // Collect ALL distinct option ids across all lines.
    List<UUID> allOptionIds =
        lines.stream().flatMap(lr -> lr.selectedOptionIds().stream()).distinct().toList();

    Map<UUID, ModifierOptionView> optionMap = new HashMap<>();
    if (!allOptionIds.isEmpty()) {
      for (int i = 0; i < allOptionIds.size(); i += 1000) {
        List<UUID> chunk = allOptionIds.subList(i, Math.min(i + 1000, allOptionIds.size()));
        optionRepository.findViewsByIds(chunk).forEach(o -> optionMap.put(o.getId(), o));
      }
    }

    // Collect ALL distinct group ids referenced by the loaded options.
    List<UUID> allGroupIds =
        optionMap.values().stream().map(ModifierOptionView::getGroupId).distinct().toList();

    Map<UUID, ModifierGroupView> groupMap = new HashMap<>();
    if (!allGroupIds.isEmpty()) {
      for (int i = 0; i < allGroupIds.size(); i += 1000) {
        List<UUID> chunk = allGroupIds.subList(i, Math.min(i + 1000, allGroupIds.size()));
        groupRepository.findViewsByIds(chunk).forEach(g -> groupMap.put(g.getId(), g));
      }
    }

    return new ValidationContext(optionMap, groupMap);
  }

  /**
   * Validates modifier selections for one order line and returns the list of resolved {@link
   * ModifierOptionView}s (de-duplicated, in encounter order).
   *
   * <p>Throws {@link IllegalArgumentException} on any of:
   *
   * <ul>
   *   <li>Duplicate option ids in {@code selectedOptionIds}.
   *   <li>Unknown or unavailable option.
   *   <li>Option whose group does not belong to {@code lineReq.menuItemId()}.
   *   <li>{@code SINGLE} group with more than 1 selection.
   *   <li>Group {@code max_select} exceeded.
   *   <li>Group {@code min_select} not met (for any group that has selections or has min_select >
   *       0).
   *   <li>Negative effective unit price.
   * </ul>
   *
   * @param lineReq the line being validated
   * @param unitPriceMinor the base unit price (minor units) for the item on this line
   * @param ctx the pre-loaded option/group maps from {@link #loadContext}
   * @return the resolved modifier views for snapshot building and delta summation
   */
  public List<ModifierOptionView> validateLine(
      OrderLineRequest lineReq, long unitPriceMinor, ValidationContext ctx) {

    List<UUID> rawIds = lineReq.selectedOptionIds();

    // --- 1. Reject duplicate option ids (double-charging bug) ---
    LinkedHashSet<UUID> dedupedIds = new LinkedHashSet<>(rawIds);
    if (dedupedIds.size() < rawIds.size()) {
      throw new IllegalArgumentException(
          "Duplicate modifier option ids in line for menu item "
              + lineReq.menuItemId()
              + ": selectedOptionIds must not contain duplicates");
    }

    List<ModifierOptionView> resolved = new ArrayList<>(dedupedIds.size());

    // Per-group selection tracking: group id -> list of selected option ids.
    // Use LinkedHashMap to keep deterministic iteration for error messages.
    Map<UUID, List<UUID>> selectionsByGroup = new LinkedHashMap<>();

    for (UUID optionId : dedupedIds) {
      ModifierOptionView option = ctx.optionMap().get(optionId);
      if (option == null) {
        throw new IllegalArgumentException("Modifier option not found or not visible: " + optionId);
      }
      if (!option.isAvailable()) {
        throw new IllegalArgumentException(
            "Modifier option is not available: " + optionId + " (" + option.getName() + ")");
      }
      ModifierGroupView group = ctx.groupMap().get(option.getGroupId());
      if (group == null || !group.getMenuItemId().equals(lineReq.menuItemId())) {
        throw new IllegalArgumentException(
            "Modifier option "
                + optionId
                + " does not belong to menu item "
                + lineReq.menuItemId());
      }
      selectionsByGroup.computeIfAbsent(option.getGroupId(), k -> new ArrayList<>()).add(optionId);
      resolved.add(option);
    }

    // --- 2. Load ALL groups for this item and enforce min/max + SINGLE ---
    List<ModifierGroupView> allGroups = groupRepository.findViewsByMenuItemId(lineReq.menuItemId());

    for (ModifierGroupView group : allGroups) {
      List<UUID> chosen = selectionsByGroup.getOrDefault(group.getId(), List.of());
      int count = chosen.size();

      // Enforce SINGLE selection type: at most 1 selection allowed.
      // A SINGLE group's effective max is 1 regardless of the stored max_select value.
      // We report this as "Too many selections" (consistent with max_select violation)
      // and note the SINGLE constraint in the message.
      int effectiveMax = "SINGLE".equals(group.getSelectionType()) ? 1 : group.getMaxSelect();
      if (count > effectiveMax) {
        String detail =
            "SINGLE".equals(group.getSelectionType())
                ? "': SINGLE-select allows at most 1, got "
                : "': max " + group.getMaxSelect() + ", got ";
        throw new IllegalArgumentException(
            "Too many selections for modifier group '" + group.getName() + detail + count);
      }

      // Enforce min_select for every group (not just required ones).
      // Use "Required modifier group" in the message for required groups so
      // existing callers can distinguish a required-group violation.
      if (count < group.getMinSelect()) {
        String prefix = group.isRequired() ? "Required modifier group" : "Modifier group";
        throw new IllegalArgumentException(
            prefix
                + " '"
                + group.getName()
                + "' requires at least "
                + group.getMinSelect()
                + " selection(s), got "
                + count);
      }
    }

    // --- 3. Compute effective unit price and reject negative ---
    long modifierDelta = 0L;
    for (ModifierOptionView opt : resolved) {
      modifierDelta = Math.addExact(modifierDelta, opt.getPriceDeltaMinor());
    }
    long effectiveUnit = Math.addExact(unitPriceMinor, modifierDelta);
    if (effectiveUnit < 0) {
      throw new IllegalArgumentException(
          "Effective unit price must be >= 0 after modifiers for menu item "
              + lineReq.menuItemId()
              + ": unitPrice="
              + unitPriceMinor
              + " + modifierDelta="
              + modifierDelta
              + " = "
              + effectiveUnit);
    }

    return resolved;
  }

  /**
   * Pre-loaded option and group maps for one validation pass. Created once via {@link #loadContext}
   * and shared across all lines in the same request.
   */
  public record ValidationContext(
      Map<UUID, ModifierOptionView> optionMap, Map<UUID, ModifierGroupView> groupMap) {}
}
