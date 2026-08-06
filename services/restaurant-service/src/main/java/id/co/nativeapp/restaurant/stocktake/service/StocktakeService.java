package id.co.nativeapp.restaurant.stocktake.service;

import id.co.nativeapp.restaurant.stocktake.domain.StocktakeStockRacedException;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeResponse;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeResult;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Facade over {@link StocktakeWriter} (ADR 0038 phase 3). Not itself {@code @Transactional} — the
 * transactional units live on the writer bean (proxy semantics, the {@code SaleService}/{@code
 * SaleWriter} pattern); this layer owns the concurrency-safe idempotency contract: exactly one
 * {@code StocktakeCompleted} per {@code (company_id, idempotency_key)}.
 *
 * <p><strong>Why the submit is a separate transaction from the conflict re-read.</strong> Two
 * concurrent submits with the same key can both pass the writer's idempotency probe and then race
 * the INSERT; the loser trips the {@code uq_stocktake_company_idempotency} unique constraint,
 * raising a {@link DataIntegrityViolationException}. A poisoned PostgreSQL transaction cannot
 * simply re-query itself, so the recovery re-read runs in a FRESH transaction ({@link
 * StocktakeWriter#findExistingByKey}), returning the winner's stocktake with {@code created=false}.
 * No second adjustment or event is emitted on that path.
 */
@Service
public class StocktakeService {

  private final StocktakeWriter writer;

  public StocktakeService(StocktakeWriter writer) {
    this.writer = writer;
  }

  /** Submits a stocktake idempotently (see class doc for the concurrent-race recovery). */
  public SubmitStocktakeResult submit(SubmitStocktakeRequest request, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.submit(request, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return writer
          .findExistingByKey(idempotencyKey)
          .map(existing -> new SubmitStocktakeResult(existing, false))
          .orElseThrow(() -> conflict);
    } catch (ObjectOptimisticLockingFailureException raced) {
      // A concurrent POS sale/refund adjusted a counted item's stock between the writer's read and
      // its flush — the whole submit rolled back (nothing persisted, no double stock-adjust, no
      // event). Surface as a retryable 409 rather than an opaque 500: a stocktake runs while the
      // outlet is online, and the console re-sends the same stable key.
      throw new StocktakeStockRacedException(raced);
    }
  }

  /** A stocktake by id, outlet-gated. */
  public StocktakeResponse findById(UUID id) {
    TenantContext.require();
    return writer.findById(id);
  }

  /** The outlet's stocktake history (most recent first, capped at 50). */
  public List<StocktakeResponse> history(UUID businessId) {
    TenantContext.require();
    return writer.findHistory(businessId);
  }
}
