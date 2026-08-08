package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the ingredient catalog (ADR 0046 phase
 * 1).
 *
 * <p>A distinct bean from {@link IngredientService} so each transactional method is invoked through
 * the Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (the {@code MenuWriter}/{@code StocktakeWriter}
 * pattern). {@link OutletAccessGuard#enforce} must run inside that same transaction (its repository
 * reads rely on the GUC already being set), so it is called here — after loading the aggregate for
 * update/deactivate/stock paths, since {@code businessId} is not known until then.
 */
@Component
public class IngredientWriter {

  private final IngredientRepository repository;
  private final OutletAccessGuard outletAccessGuard;

  public IngredientWriter(IngredientRepository repository, OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Creates and persists a new active {@link Ingredient} in its own transaction. The {@code
   * company_id} is stamped from the bound tenant scope, never from the request body (rule 5) —
   * required explicitly because the FORCE-RLS {@code WITH CHECK} policy rejects an insert whose
   * {@code company_id} does not match the session tenant.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse create(CreateIngredientRequest request) {
    String companyId = TenantContext.require().companyId();
    outletAccessGuard.enforce(request.businessId());

    Ingredient ingredient =
        new Ingredient(
            request.businessId(),
            request.name(),
            request.unit(),
            request.unitCostMinor(),
            request.costCurrency());
    ingredient.setCompanyId(companyId);
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Applies a partial update ({@code PATCH}) to an existing ingredient. RLS restricts the load to
   * the current tenant — an ingredient from another company is invisible and triggers the same 404
   * as a genuinely missing one.
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse update(UUID id, UpdateIngredientRequest request) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.update(
        request.name(), request.unit(), request.unitCostMinor(), request.costCurrency());
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Soft-deactivates an ingredient by calling {@link Ingredient#deactivate()}, which sets {@code
   * active = false}. The row disappears from {@code GET /api/v1/ingredients} (which filters to
   * active rows) but historical ingredient-stocktake lines that reference it are unaffected.
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deactivate(UUID id) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.deactivate();
    repository.saveAndFlush(ingredient);
  }

  /**
   * Sets the absolute stock quantity for an ingredient (always tracked — no infinite/untracked
   * state, ADR 0046).
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   * @throws IllegalArgumentException if {@code quantity} is negative
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse setStock(UUID id, int quantity) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.setStock(quantity);
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Adds a signed delta to an ingredient's stock, flooring at 0.
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse addStock(UUID id, int amount) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.addStock(amount);
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  private Ingredient load(UUID id) {
    return repository.findById(id).orElseThrow(() -> new IngredientNotFoundException(id));
  }
}
