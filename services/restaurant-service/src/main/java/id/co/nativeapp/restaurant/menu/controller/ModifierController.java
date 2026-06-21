package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Modifier group and option management (Phase 3 catalog richness).
 *
 * <ul>
 *   <li>{@code GET /api/v1/menu/{menuItemId}/modifier-groups} — list groups+options for a menu
 *       item. Add {@code ?adminView=true} to include unavailable options.
 *   <li>{@code POST /api/v1/menu/{menuItemId}/modifier-groups} — create a modifier group.
 *   <li>{@code POST /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options} — add an option.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/86} —
 *       mark option unavailable.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/un-86}
 *       — restore.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/86} — mark the menu item unavailable.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/un-86} — restore the menu item's availability.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/menu")
public class ModifierController {

  private final ModifierService modifierService;

  public ModifierController(ModifierService modifierService) {
    this.modifierService = modifierService;
  }

  /**
   * Lists modifier groups (with their options) for a menu item. By default returns only available
   * options (cashier view); pass {@code adminView=true} to include unavailable options.
   */
  @GetMapping("/{menuItemId}/modifier-groups")
  public ResponseEntity<List<ModifierGroupResponse>> listGroups(
      @PathVariable UUID menuItemId, @RequestParam(defaultValue = "false") boolean adminView) {
    List<ModifierGroupResponse> groups =
        adminView
            ? modifierService.findGroupsWithAllOptions(menuItemId)
            : modifierService.findGroupsWithOptions(menuItemId);
    return ResponseEntity.ok(groups);
  }

  /**
   * Creates a modifier group under a menu item. Returns {@code 201 Created} with a {@code Location}
   * header.
   */
  @PostMapping("/{menuItemId}/modifier-groups")
  public ResponseEntity<ModifierGroupResponse> createGroup(
      @PathVariable UUID menuItemId, @Valid @RequestBody CreateModifierGroupRequest request) {
    ModifierGroupResponse created = modifierService.createGroup(menuItemId, request);
    return ResponseEntity.created(
            URI.create("/api/v1/menu/" + menuItemId + "/modifier-groups/" + created.id()))
        .body(created);
  }

  /**
   * Adds an option to a modifier group. Returns {@code 201 Created} with a {@code Location} header.
   */
  @PostMapping("/{menuItemId}/modifier-groups/{groupId}/options")
  public ResponseEntity<ModifierOptionResponse> createOption(
      @PathVariable UUID menuItemId,
      @PathVariable UUID groupId,
      @Valid @RequestBody CreateModifierOptionRequest request) {
    ModifierOptionResponse created = modifierService.createOption(groupId, request);
    return ResponseEntity.created(
            URI.create(
                "/api/v1/menu/"
                    + menuItemId
                    + "/modifier-groups/"
                    + groupId
                    + "/options/"
                    + created.id()))
        .body(created);
  }

  /** Marks a modifier option as unavailable (86). */
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/86")
  public ResponseEntity<ModifierOptionResponse> markOptionUnavailable(
      @PathVariable UUID menuItemId, @PathVariable UUID groupId, @PathVariable UUID optionId) {
    return ResponseEntity.ok(modifierService.markOptionUnavailable(optionId));
  }

  /** Marks a modifier option as available again (un-86). */
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/un-86")
  public ResponseEntity<ModifierOptionResponse> markOptionAvailable(
      @PathVariable UUID menuItemId, @PathVariable UUID groupId, @PathVariable UUID optionId) {
    return ResponseEntity.ok(modifierService.markOptionAvailable(optionId));
  }

  /** Marks a menu item as unavailable for ordering (86). */
  @PatchMapping("/{menuItemId}/86")
  public ResponseEntity<Void> markItemUnavailable(@PathVariable UUID menuItemId) {
    modifierService.markItemUnavailable(menuItemId);
    return ResponseEntity.noContent().build();
  }

  /** Marks a menu item as available for ordering again (un-86). */
  @PatchMapping("/{menuItemId}/un-86")
  public ResponseEntity<Void> markItemAvailable(@PathVariable UUID menuItemId) {
    modifierService.markItemAvailable(menuItemId);
    return ResponseEntity.noContent().build();
  }
}
