package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates modifier group and option operations.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link ModifierWriter} and
 * {@link ModifierReader} so the proxy and the RLS aspect engage.
 */
@Service
public class ModifierService {

  private final ModifierWriter writer;
  private final ModifierReader reader;

  public ModifierService(ModifierWriter writer, ModifierReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /** Returns modifier groups (with available options) for the cashier view. */
  public List<ModifierGroupResponse> findGroupsWithOptions(UUID menuItemId) {
    TenantContext.require();
    return reader.findGroupsWithOptions(menuItemId);
  }

  /** Returns modifier groups (with ALL options including unavailable) for the admin view. */
  public List<ModifierGroupResponse> findGroupsWithAllOptions(UUID menuItemId) {
    TenantContext.require();
    return reader.findGroupsWithAllOptions(menuItemId);
  }

  /** Creates a modifier group under a menu item. */
  public ModifierGroupResponse createGroup(UUID menuItemId, CreateModifierGroupRequest request) {
    TenantContext.require();
    return writer.createGroup(menuItemId, request);
  }

  /** Creates a modifier option under a modifier group. */
  public ModifierOptionResponse createOption(UUID groupId, CreateModifierOptionRequest request) {
    TenantContext.require();
    return writer.createOption(groupId, request);
  }

  /** Marks a modifier option as unavailable (86'd). */
  public ModifierOptionResponse markOptionUnavailable(UUID optionId) {
    TenantContext.require();
    return writer.markOptionUnavailable(optionId);
  }

  /** Marks a modifier option as available again. */
  public ModifierOptionResponse markOptionAvailable(UUID optionId) {
    TenantContext.require();
    return writer.markOptionAvailable(optionId);
  }

  /** Marks a menu item as unavailable for ordering (86). */
  public void markItemUnavailable(UUID menuItemId) {
    TenantContext.require();
    writer.markItemUnavailable(menuItemId);
  }

  /** Marks a menu item as available for ordering again (un-86). */
  public void markItemAvailable(UUID menuItemId) {
    TenantContext.require();
    writer.markItemAvailable(menuItemId);
  }
}
