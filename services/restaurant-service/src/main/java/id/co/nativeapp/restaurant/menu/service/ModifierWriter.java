package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.domain.ModifierGroup;
import id.co.nativeapp.restaurant.menu.domain.ModifierOption;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierGroupRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for modifier groups and options.
 *
 * <p>A distinct bean from {@link ModifierService} so each transactional method is invoked through
 * the Spring proxy (same pattern as {@code MenuWriter}). {@link RlsAutoApplyAspect} applies the
 * tenant GUC on every method entry.
 */
@Component
public class ModifierWriter {

  private final MenuItemRepository menuItemRepository;
  private final ModifierGroupRepository groupRepository;
  private final ModifierOptionRepository optionRepository;

  public ModifierWriter(
      MenuItemRepository menuItemRepository,
      ModifierGroupRepository groupRepository,
      ModifierOptionRepository optionRepository) {
    this.menuItemRepository = menuItemRepository;
    this.groupRepository = groupRepository;
    this.optionRepository = optionRepository;
  }

  /** Creates a modifier group under a menu item. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ModifierGroupResponse createGroup(UUID menuItemId, CreateModifierGroupRequest request) {
    String companyId = TenantContext.require().companyId();
    // Load the menu item to confirm it exists and is visible (RLS-scoped).
    // Derive business_id from the loaded entity — never trust the request body
    // (fixing finding #5: business_id trusted from request body).
    MenuItem item =
        menuItemRepository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    UUID businessId = item.getBusinessId();

    if (request.maxSelect() < request.minSelect()) {
      throw new IllegalArgumentException(
          "maxSelect ("
              + request.maxSelect()
              + ") must be >= minSelect ("
              + request.minSelect()
              + ")");
    }
    ModifierGroup group =
        new ModifierGroup(
            menuItemId,
            businessId,
            request.name(),
            request.selectionType(),
            request.required(),
            request.minSelect(),
            request.maxSelect(),
            request.displayOrder());
    group.setCompanyId(companyId);
    ModifierGroup saved = groupRepository.saveAndFlush(group);
    return ModifierGroupResponse.from(saved);
  }

  /** Creates a modifier option under a modifier group. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ModifierOptionResponse createOption(UUID groupId, CreateModifierOptionRequest request) {
    String companyId = TenantContext.require().companyId();
    // Load the group to confirm it exists and is visible (RLS-scoped).
    // Derive business_id from the loaded group entity — never trust the request body
    // (fixing finding #5: business_id trusted from request body).
    ModifierGroup group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NoSuchElementException("Modifier group not found: " + groupId));
    UUID businessId = group.getBusinessId();

    ModifierOption option =
        new ModifierOption(
            groupId, businessId, request.name(), request.priceDeltaMinor(), request.displayOrder());
    option.setCompanyId(companyId);
    ModifierOption saved = optionRepository.saveAndFlush(option);
    return ModifierOptionResponse.from(saved);
  }

  /** Marks a modifier option as unavailable (86'd). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ModifierOptionResponse markOptionUnavailable(UUID optionId) {
    TenantContext.require();
    ModifierOption option =
        optionRepository
            .findById(optionId)
            .orElseThrow(
                () -> new NoSuchElementException("Modifier option not found: " + optionId));
    option.markUnavailable();
    ModifierOption saved = optionRepository.saveAndFlush(option);
    return ModifierOptionResponse.from(saved);
  }

  /** Marks a modifier option as available again (un-86). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ModifierOptionResponse markOptionAvailable(UUID optionId) {
    TenantContext.require();
    ModifierOption option =
        optionRepository
            .findById(optionId)
            .orElseThrow(
                () -> new NoSuchElementException("Modifier option not found: " + optionId));
    option.markAvailable();
    ModifierOption saved = optionRepository.saveAndFlush(option);
    return ModifierOptionResponse.from(saved);
  }

  /** Marks a menu item as unavailable (86'd). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markItemUnavailable(UUID menuItemId) {
    TenantContext.require();
    MenuItem item =
        menuItemRepository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    item.markUnavailable();
    menuItemRepository.saveAndFlush(item);
  }

  /** Marks a menu item as available again (un-86). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markItemAvailable(UUID menuItemId) {
    TenantContext.require();
    MenuItem item =
        menuItemRepository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    item.markAvailable();
    menuItemRepository.saveAndFlush(item);
  }
}
