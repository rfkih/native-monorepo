package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
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
   */
  public IngredientResponse create(CreateIngredientRequest request) {
    TenantContext.require();
    return writer.create(request);
  }

  /** Applies a partial update to an ingredient. */
  public IngredientResponse update(UUID id, UpdateIngredientRequest request) {
    TenantContext.require();
    return writer.update(id, request);
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
