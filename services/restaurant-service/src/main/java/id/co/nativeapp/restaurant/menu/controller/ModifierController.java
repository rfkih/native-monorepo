package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.UpdateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}} — edit a group (patch
 *       semantics; only non-null fields applied).
 *   <li>{@code DELETE /api/v1/menu/{menuItemId}/modifier-groups/{groupId}} — hard-delete a group
 *       and all its options (safe: order/bill tables snapshot, no FK).
 *   <li>{@code POST /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options} — add an option.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}} —
 *       edit an option (patch semantics).
 *   <li>{@code DELETE /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}} —
 *       hard-delete an option (safe: order/bill tables snapshot, no FK).
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/86} —
 *       mark option unavailable.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/un-86}
 *       — restore.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/86} — mark the menu item unavailable.
 *   <li>{@code PATCH /api/v1/menu/{menuItemId}/un-86} — restore the menu item's availability.
 * </ul>
 */
@Tag(
    name = "Menu Modifiers",
    description = "Modifier group/option management and 86/un-86 availability control")
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
  @Operation(
      summary = "List modifier groups for a menu item",
      description =
          "Lists modifier groups (with their options) for a menu item. By default returns only"
              + " available options (cashier view); pass adminView=true to include unavailable"
              + " options.")
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
  @Operation(
      summary = "Create a modifier group",
      description =
          "Creates a modifier group under a menu item. Returns 201 Created with a Location header.")
  @PostMapping("/{menuItemId}/modifier-groups")
  public ResponseEntity<ModifierGroupResponse> createGroup(
      @PathVariable UUID menuItemId, @Valid @RequestBody CreateModifierGroupRequest request) {
    ModifierGroupResponse created = modifierService.createGroup(menuItemId, request);
    return ResponseEntity.created(
            URI.create("/api/v1/menu/" + menuItemId + "/modifier-groups/" + created.id()))
        .body(created);
  }

  /**
   * Edits a modifier group (patch semantics — only non-null fields are applied). Returns the
   * updated group. 404 if the group is not found or not visible to the current tenant.
   */
  @Operation(
      summary = "Edit a modifier group (patch semantics)",
      description =
          "Applies a partial update to a modifier group. Only non-null fields in the request body"
              + " are applied. The same invariants as create apply (maxSelect >= minSelect,"
              + " SINGLE => maxSelect 1). Returns 404 if the group does not exist or is not"
              + " visible to the current tenant.")
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}")
  public ResponseEntity<ModifierGroupResponse> updateGroup(
      @PathVariable UUID menuItemId,
      @PathVariable UUID groupId,
      @Valid @RequestBody UpdateModifierGroupRequest request) {
    return ResponseEntity.ok(modifierService.updateGroup(groupId, request));
  }

  /**
   * Hard-deletes a modifier group and all its options. Safe because {@code order_line_modifier} and
   * {@code bill_line_modifier} snapshot the option name and price at order time (no enforced FK).
   * Returns {@code 204 No Content}. 404 if the group is not found or not visible to the current
   * tenant.
   */
  @Operation(
      summary = "Delete a modifier group and all its options",
      description =
          "Hard-deletes the modifier group and all its options. Safe because order/bill tables"
              + " snapshot the option name and price at order time (no enforced FK to the live"
              + " option). Returns 204 No Content. Returns 404 if the group does not exist or is"
              + " not visible to the current tenant.")
  @DeleteMapping("/{menuItemId}/modifier-groups/{groupId}")
  public ResponseEntity<Void> deleteGroup(
      @PathVariable UUID menuItemId, @PathVariable UUID groupId) {
    modifierService.deleteGroup(groupId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Adds an option to a modifier group. Returns {@code 201 Created} with a {@code Location} header.
   */
  @Operation(
      summary = "Add an option to a modifier group",
      description =
          "Adds an option to a modifier group. Returns 201 Created with a Location header.")
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

  /**
   * Edits a modifier option (patch semantics — only non-null fields are applied). Returns the
   * updated option. 404 if the option is not found or not visible to the current tenant.
   */
  @Operation(
      summary = "Edit a modifier option (patch semantics)",
      description =
          "Applies a partial update to a modifier option. Only non-null fields in the request body"
              + " are applied. Returns 404 if the option does not exist or is not visible to the"
              + " current tenant.")
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}")
  public ResponseEntity<ModifierOptionResponse> updateOption(
      @PathVariable UUID menuItemId,
      @PathVariable UUID groupId,
      @PathVariable UUID optionId,
      @Valid @RequestBody UpdateModifierOptionRequest request) {
    return ResponseEntity.ok(modifierService.updateOption(optionId, request));
  }

  /**
   * Hard-deletes a modifier option. Safe because {@code order_line_modifier} and {@code
   * bill_line_modifier} snapshot the option name and price at order time (no enforced FK). Returns
   * {@code 204 No Content}. 404 if the option is not found or not visible to the current tenant.
   */
  @Operation(
      summary = "Delete a modifier option",
      description =
          "Hard-deletes the modifier option. Safe because order/bill tables snapshot the option"
              + " name and price at order time (no enforced FK to the live option). Returns 204 No"
              + " Content. Returns 404 if the option does not exist or is not visible to the"
              + " current tenant.")
  @DeleteMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}")
  public ResponseEntity<Void> deleteOption(
      @PathVariable UUID menuItemId,
      @PathVariable UUID groupId,
      @PathVariable UUID optionId) {
    modifierService.deleteOption(optionId);
    return ResponseEntity.noContent().build();
  }

  /** Marks a modifier option as unavailable (86). */
  @Operation(
      summary = "Mark a modifier option unavailable (86)",
      description = "Marks a modifier option as unavailable (86) — hides it from the cashier.")
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/86")
  public ResponseEntity<ModifierOptionResponse> markOptionUnavailable(
      @PathVariable UUID menuItemId, @PathVariable UUID groupId, @PathVariable UUID optionId) {
    return ResponseEntity.ok(modifierService.markOptionUnavailable(optionId));
  }

  /** Marks a modifier option as available again (un-86). */
  @Operation(
      summary = "Restore a modifier option availability (un-86)",
      description = "Marks a modifier option as available again (un-86).")
  @PatchMapping("/{menuItemId}/modifier-groups/{groupId}/options/{optionId}/un-86")
  public ResponseEntity<ModifierOptionResponse> markOptionAvailable(
      @PathVariable UUID menuItemId, @PathVariable UUID groupId, @PathVariable UUID optionId) {
    return ResponseEntity.ok(modifierService.markOptionAvailable(optionId));
  }

  /** Marks a menu item as unavailable for ordering (86). */
  @Operation(
      summary = "Mark a menu item unavailable (86)",
      description = "Marks a menu item as unavailable for ordering (86).")
  @PatchMapping("/{menuItemId}/86")
  public ResponseEntity<Void> markItemUnavailable(@PathVariable UUID menuItemId) {
    modifierService.markItemUnavailable(menuItemId);
    return ResponseEntity.noContent().build();
  }

  /** Marks a menu item as available for ordering again (un-86). */
  @Operation(
      summary = "Restore a menu item availability (un-86)",
      description = "Marks a menu item as available for ordering again (un-86).")
  @PatchMapping("/{menuItemId}/un-86")
  public ResponseEntity<Void> markItemAvailable(@PathVariable UUID menuItemId) {
    modifierService.markItemAvailable(menuItemId);
    return ResponseEntity.noContent().build();
  }
}
