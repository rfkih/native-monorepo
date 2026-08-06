package id.co.nativeapp.finance.stocktake.service;

import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Binds the {@code StocktakeCompleted} consumer path to the EVENT's tenant (ADR 0038 phase 3).
 * There is no JWT on a Kafka consumer; {@link TenantContext#callAs} scopes the handler to the
 * event's {@code company_id} with the fixed {@code finance-consumer} actor so RLS + Auditable
 * stamping apply — the {@code RegisterCloseService} pattern. The transactional dedupe + posting
 * lives in the proxied {@link StocktakeWriter} (separate bean; self-invocation would bypass the
 * advice).
 */
@Service
public class StocktakeService {

  private static final String CONSUMER_ACTOR = "finance-consumer";

  private final StocktakeWriter writer;

  public StocktakeService(StocktakeWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded event under the event's tenant.
   *
   * @return true when this delivery performed the posting; false when it was a re-delivery
   */
  public boolean handle(StocktakeCompletedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.post(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.post throws only unchecked.
      throw new IllegalStateException("StocktakeCompleted handling failed", e);
    }
  }
}
