package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.projection.IngredientView;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional unit for the ingredient catalog (ADR 0046 phase 1). A distinct bean from
 * {@link IngredientService} so the Spring proxy applies the transaction + RLS-GUC advice ({@link
 * id.co.nativeapp.tenant.RlsAutoApplyAspect}) — the {@code MenuReader} pattern.
 *
 * <p>Unlike the legacy {@code MenuReader} (menu catalog reads carry no outlet-assignment check),
 * the ingredient list is outlet-scoped operational stock data — built at the same maturity level as
 * the stocktake feature (ADR 0038 phase 3), so it enforces {@link OutletAccessGuard} like every
 * other ingredient-inventory path, not the legacy menu precedent.
 */
@Component
public class IngredientReader {

  private final IngredientRepository repository;
  private final OutletAccessGuard outletAccessGuard;

  public IngredientReader(IngredientRepository repository, OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /** Active ingredients for an outlet, scoped to the bound tenant by RLS. */
  @Transactional(readOnly = true)
  public List<IngredientResponse> findByBusiness(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    return repository.findActiveByBusiness(businessId).stream()
        .map(IngredientReader::toResponse)
        .toList();
  }

  /**
   * Maps a read-path projection to the response shape. Lives in the service layer so the ArchUnit
   * rule ({@code projection} accessed only by {@code service} and {@code repository}) is respected.
   */
  static IngredientResponse toResponse(IngredientView view) {
    return new IngredientResponse(
        view.getId(),
        view.getBusinessId(),
        view.getName(),
        view.getUnit(),
        view.getDisplayUnit(),
        view.getStockQty(),
        view.getUnitCostMinor(),
        view.getCostCurrency() == null ? null : view.getCostCurrency().strip(),
        view.getStockValueMinor(),
        view.isActive());
  }
}
