package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.recipe.domain.RecipeAutoLinkBlockedException;
import id.co.nativeapp.restaurant.recipe.domain.RecipeLine;
import id.co.nativeapp.restaurant.recipe.dto.AutoLinkResult;
import id.co.nativeapp.restaurant.recipe.projection.UnlinkedMenuItemView;
import id.co.nativeapp.restaurant.recipe.repository.RecipeLineRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Lacak stok otomatis" — the 1:1 auto-link (ADR 0050 follow-up, 2026-08-16): for a menu item with
 * NO recipe, mint a same-named ingredient (unit {@code pcs}, stock 0, no cost) and a base recipe
 * line of 1 per portion, so every sale of the item starts depleting stock via the EXISTING per-sale
 * depletion — and the stock opname's prefilled figures start moving. The auto-recipe is a starting
 * point, not a lock-in: the owner refines it into real bahan via the normal recipe PUT.
 *
 * <p>Idempotent by construction: items that already have ANY recipe line are skipped; an ACTIVE
 * same-named ingredient at the outlet is reused (the V31 name unique makes at most one exist)
 * instead of minting a duplicate. Distinct writer bean so {@code @Transactional} + the RLS-GUC
 * aspect bind on proxy invocation (the {@code RecipeWriter} pattern).
 */
@Component
public class RecipeAutoLinkWriter {

  /** The minted ingredient's opaque base unit — a whole portion piece. */
  static final String AUTO_UNIT = "pcs";

  private final RecipeLineRepository recipeLineRepository;
  private final MenuItemRepository menuItemRepository;
  private final IngredientRepository ingredientRepository;
  private final OutletAccessGuard outletAccessGuard;

  public RecipeAutoLinkWriter(
      RecipeLineRepository recipeLineRepository,
      MenuItemRepository menuItemRepository,
      IngredientRepository ingredientRepository,
      OutletAccessGuard outletAccessGuard) {
    this.recipeLineRepository = recipeLineRepository;
    this.menuItemRepository = menuItemRepository;
    this.ingredientRepository = ingredientRepository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Links every ACTIVE menu item of the outlet that has no recipe ("Lacak stok semua menu"). One
   * transaction — the sweep either fully applies or not at all (a concurrent same-name create can
   * abort it via the V31 unique; the user simply retries).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AutoLinkResult autoLinkAll(UUID businessId) {
    String companyId = TenantContext.require().companyId();
    outletAccessGuard.enforce(businessId);

    List<UnlinkedMenuItemView> unlinked = recipeLineRepository.findUnlinkedActiveItems(businessId);
    long activeTotal = menuItemRepository.findActiveByBusiness(businessId).size();
    int linked = 0;
    int blocked = 0;
    for (UnlinkedMenuItemView item : unlinked) {
      // A same-named non-pcs ingredient blocks this one — tally it, never crash the whole sweep.
      if (link(businessId, item.getId(), item.getName(), companyId)) {
        linked++;
      } else {
        blocked++;
      }
    }
    // "skipped" = active items that already HAD a recipe (untouched); "blocked" = recipe-less items
    // a name-collision with an incompatible ingredient prevented linking.
    return new AutoLinkResult(linked, (int) Math.max(0, activeTotal - unlinked.size()), blocked);
  }

  /**
   * Links ONE menu item (the RecipeDrawer 1-klik and the create-menu checkbox). A no-op when the
   * item already has a recipe — never overwrites an owner's real recipe.
   *
   * @throws NoSuchElementException if the menu item is unknown/cross-tenant (→ 404)
   * @throws RecipeAutoLinkBlockedException if a same-named non-{@code pcs} ingredient blocks it
   *     (422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void autoLinkItem(UUID menuItemId) {
    String companyId = TenantContext.require().companyId();
    MenuItem item =
        menuItemRepository
            .findById(menuItemId)
            .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + menuItemId));
    outletAccessGuard.enforce(item.getBusinessId());
    if (recipeLineRepository.existsByMenuItemId(menuItemId)) {
      return; // already has a recipe — idempotent no-op
    }
    Ingredient existing = findReusable(item.getBusinessId(), item.getName());
    if (blockedBy(existing)) {
      throw new RecipeAutoLinkBlockedException(item.getName(), existing.getUnit());
    }
    linkTo(item.getBusinessId(), menuItemId, item.getName(), companyId, existing);
  }

  /**
   * Finds a REUSABLE same-named ingredient, mints one when absent, and inserts the 1:1 base line —
   * returns {@code true} when linked, {@code false} when BLOCKED by a same-named non-{@code pcs}
   * ingredient (the bulk sweep tallies it instead of throwing).
   */
  private boolean link(UUID businessId, UUID menuItemId, String itemName, String companyId) {
    Ingredient existing = findReusable(businessId, itemName);
    if (blockedBy(existing)) {
      return false;
    }
    linkTo(businessId, menuItemId, itemName, companyId, existing);
    return true;
  }

  /**
   * The active same-named ingredient (CASE-INSENSITIVE — the V31 unique is on {@code lower(name)},
   * so a case-sensitive probe would miss it then collide on mint, review C1), or null.
   */
  private Ingredient findReusable(UUID businessId, String itemName) {
    return ingredientRepository
        .findFirstByBusinessIdAndNameIgnoreCaseAndActiveTrue(businessId, itemName)
        .orElse(null);
  }

  /**
   * A same-named ingredient exists but is NOT a whole-piece {@code pcs} item — reusing it 1/portion
   * would silently deplete/​revalue a raw material on every sale (review W2), so linking is
   * blocked.
   */
  private static boolean blockedBy(Ingredient existing) {
    // Case-insensitive on unit: a "Pcs"/"PCS" ingredient is still a whole-piece item and should be
    // reused, not blocked (the console only emits lowercase, but be robust to hand-seeded data).
    return existing != null && !AUTO_UNIT.equalsIgnoreCase(existing.getUnit());
  }

  /**
   * Reuse the compatible {@code existing}, or mint a fresh pcs/stock-0 ingredient, then link 1:1.
   */
  private void linkTo(
      UUID businessId, UUID menuItemId, String itemName, String companyId, Ingredient existing) {
    UUID ingredientId;
    if (existing != null) {
      ingredientId = existing.getId();
    } else {
      // Uncosted, stock 0 — the owner seeds the count via the first opname/receive.
      Ingredient ingredient = new Ingredient(businessId, itemName, AUTO_UNIT, null, null);
      ingredient.setCompanyId(companyId);
      ingredientId = ingredientRepository.saveAndFlush(ingredient).getId();
    }
    RecipeLine line = new RecipeLine(businessId, menuItemId, ingredientId, null, 1);
    line.setCompanyId(companyId);
    recipeLineRepository.saveAndFlush(line);
  }
}
