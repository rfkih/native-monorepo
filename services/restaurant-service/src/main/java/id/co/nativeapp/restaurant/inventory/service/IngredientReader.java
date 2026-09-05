package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStockDayResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStockSummaryResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientUsageResponse;
import id.co.nativeapp.restaurant.inventory.projection.IngredientView;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStockDayRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import java.time.LocalDate;
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
  private final IngredientStockDayRepository stockDayRepository;
  private final OutletAccessGuard outletAccessGuard;

  public IngredientReader(
      IngredientRepository repository,
      IngredientStockDayRepository stockDayRepository,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.stockDayRepository = stockDayRepository;
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
   * Per-ingredient quantity consumed by sales on one outlet-local day ("terpakai", V42) — feeds the
   * opname sheet's "terpakai hari ini" and the opname-history detail. An ingredient with no sales
   * that day simply has no row (the client treats absence as 0).
   */
  @Transactional(readOnly = true)
  public List<IngredientUsageResponse> usageForDate(UUID businessId, LocalDate date) {
    outletAccessGuard.enforce(businessId);
    return stockDayRepository.findByBusinessIdAndDate(businessId, date).stream()
        .map(v -> new IngredientUsageResponse(v.getIngredientId(), v.getQtyUsed()))
        .toList();
  }

  /**
   * An outlet's per-ingredient movement roll-up over {@code [from, to]} (inclusive) — the "riwayat
   * stok" table (V47). One row per ingredient that moved at all in the window; ingredients that did
   * not move are absent.
   *
   * <p>Returns totals and counts, never an average: the client divides {@code totalUsedQty} by
   * whichever denominator it means and formats with locale-aware {@code Intl}, so the rounding
   * choice is made where the presentation is.
   */
  @Transactional(readOnly = true)
  public List<IngredientStockSummaryResponse> stockSummary(
      UUID businessId, LocalDate from, LocalDate to) {
    outletAccessGuard.enforce(businessId);
    return stockDayRepository.findStockSummary(businessId, from, to).stream()
        .map(
            v ->
                new IngredientStockSummaryResponse(
                    v.getIngredientId(),
                    v.getName(),
                    v.getUnit(),
                    v.getTotalUsedQty(),
                    v.getTotalReceivedQty(),
                    v.getNetAdjustmentQty(),
                    v.getTotalWasteQty(),
                    v.getReceiptCount(),
                    v.getAdjustmentCount(),
                    v.getDaysWithMovement(),
                    v.getDaysWithUsage(),
                    v.getLatestClosingQty()))
        .toList();
  }

  /**
   * One ingredient's day-by-day ledger across {@code [from, to]} (inclusive), oldest first — the
   * drill-down behind a row of {@link #stockSummary}. Days with no movement are ABSENT rather than
   * zero-filled: the client carries the previous row's closing balance across the gap, which is
   * what actually happened.
   *
   * <p>Outlet access is enforced via the ingredient's OWN outlet, read back through RLS — an
   * ingredient belonging to another tenant is invisible and 404s exactly like a missing one, so
   * this cannot be used to probe for ids.
   */
  @Transactional(readOnly = true)
  public List<IngredientStockDayResponse> stockHistory(
      UUID ingredientId, LocalDate from, LocalDate to) {
    UUID businessId =
        repository
            .findBusinessIdById(ingredientId)
            .orElseThrow(() -> new IngredientNotFoundException(ingredientId));
    outletAccessGuard.enforce(businessId);
    return stockDayRepository.findDailyLedger(ingredientId, from, to).stream()
        .map(
            v ->
                new IngredientStockDayResponse(
                    v.getStockDate(),
                    v.getQtyUsed(),
                    v.getReceivedQty(),
                    v.getAdjustmentQty(),
                    v.getWasteQty(),
                    v.getReceiptCount(),
                    v.getAdjustmentCount(),
                    v.getClosingQty()))
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
        view.isActive(),
        view.getPackSize());
  }
}
