package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeResponse;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeStockRacedException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Facade over {@link IngredientStocktakeWriter} (ADR 0046 phase 1). A clone of {@code
 * id.co.nativeapp.restaurant.stocktake.service.StocktakeService} (ADR 0038 phase 3). Not itself
 * {@code @Transactional} — the transactional units live on the writer bean (proxy semantics); this
 * layer owns the concurrency-safe idempotency contract: exactly one {@code StocktakeCompleted} per
 * {@code (company_id, idempotency_key)} for a costed count, zero events for an uncosted one.
 *
 * <p><strong>Why the submit is a separate transaction from the conflict re-read.</strong> Two
 * concurrent submits with the same key can both pass the writer's idempotency probe and then race
 * the INSERT; the loser trips {@code uq_ingredient_stocktake_company_idempotency}, raising a {@link
 * DataIntegrityViolationException}. A poisoned PostgreSQL transaction cannot simply re-query
 * itself, so the recovery re-read runs in a FRESH transaction ({@link
 * IngredientStocktakeWriter#findExistingByKey}), returning the winner's stocktake with {@code
 * created=false}. No second adjustment or event is emitted on that path.
 */
@Service
public class IngredientStocktakeService {

  private final IngredientStocktakeWriter writer;

  public IngredientStocktakeService(IngredientStocktakeWriter writer) {
    this.writer = writer;
  }

  /**
   * Submits an ingredient stocktake idempotently (see class doc for the concurrent-race recovery).
   */
  public SubmitIngredientStocktakeResult submit(
      SubmitIngredientStocktakeRequest request, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.submit(request, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return writer
          .findExistingByKey(idempotencyKey)
          .map(existing -> new SubmitIngredientStocktakeResult(existing, false))
          .orElseThrow(() -> conflict);
    } catch (ObjectOptimisticLockingFailureException raced) {
      // A concurrent POS sale/refund adjusted a counted ingredient's stock between the writer's
      // read
      // and its flush — the whole submit rolled back (nothing persisted, no double stock-adjust, no
      // event). Surface as a retryable 409 rather than an opaque 500 (the StocktakeService
      // pattern).
      throw new StocktakeStockRacedException(raced);
    }
  }

  /** An ingredient stocktake by id, outlet-gated. */
  public IngredientStocktakeResponse findById(UUID id) {
    TenantContext.require();
    return writer.findById(id);
  }

  /** The outlet's ingredient stocktake history (most recent first, capped at 50). */
  public List<IngredientStocktakeResponse> history(UUID businessId) {
    TenantContext.require();
    return writer.findHistory(businessId);
  }
}
