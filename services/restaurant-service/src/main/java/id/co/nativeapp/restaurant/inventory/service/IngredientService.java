package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.domain.IngredientNameConflictException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ingredient-catalog read and write operations (ADR 0046 phase 1). Not itself
 * {@code @Transactional} — transactional units of work live in {@link IngredientWriter} and {@link
 * IngredientReader} so the Spring proxy and the RLS aspect engage (the {@code MenuService}
 * pattern). Every delegated call is also outlet-scoped via {@code OutletAccessGuard}, enforced
 * inside the transactional writer/reader methods themselves (see their class docs).
 */
@Service
public class IngredientService {

  private final IngredientWriter writer;
  private final IngredientReader reader;

  public IngredientService(IngredientWriter writer, IngredientReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /** Active ingredients for an outlet, scoped to the bound tenant by RLS. */
  public List<IngredientResponse> findByBusiness(UUID businessId) {
    TenantContext.require();
    return reader.findByBusiness(businessId);
  }

  /**
   * Creates a new active ingredient. The {@code company_id} is stamped inside {@link
   * IngredientWriter#create} from the bound tenant scope, never from the request body (rule 5).
   *
   * @throws IngredientNameConflictException if an ACTIVE ingredient with the same name already
   *     exists at the outlet (the V31 partial unique index; caught here AFTER the writer's {@code
   *     REQUIRES_NEW} transaction aborted — the {@code StocktakeService} pattern)
   */
  public IngredientResponse create(CreateIngredientRequest request) {
    TenantContext.require();
    try {
      return writer.create(request);
    } catch (DataIntegrityViolationException ex) {
      throw asNameConflict(ex, request.name());
    }
  }

  /** Applies a partial update to an ingredient (a rename can hit the same name unique). */
  public IngredientResponse update(UUID id, UpdateIngredientRequest request) {
    TenantContext.require();
    try {
      return writer.update(id, request);
    } catch (DataIntegrityViolationException ex) {
      throw asNameConflict(ex, request.name());
    }
  }

  /**
   * Maps the name-unique violation to its domain fault; any OTHER integrity violation is rethrown
   * untouched (never masquerade an unknown constraint as a name conflict).
   */
  private static RuntimeException asNameConflict(DataIntegrityViolationException ex, String name) {
    String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
    if (message.contains("uq_ingredient_company_business_name")) {
      return new IngredientNameConflictException(name);
    }
    return ex;
  }

  /** Soft-deactivates an ingredient (sets {@code active = false}). */
  public void deactivate(UUID id) {
    TenantContext.require();
    writer.deactivate(id);
  }

  /** Sets the absolute stock quantity for an ingredient. */
  public IngredientResponse setStock(UUID id, int quantity) {
    TenantContext.require();
    return writer.setStock(id, quantity);
  }

  /** Adds a signed delta to an ingredient's stock, flooring at 0. */
  public IngredientResponse addStock(UUID id, int amount) {
    TenantContext.require();
    return writer.addStock(id, amount);
  }
}
