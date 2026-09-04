package id.co.nativeapp.finance.inventory.service;

import id.co.nativeapp.finance.inventory.messaging.StockReceivedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Binds the {@code StockReceived} consumer path to the EVENT's tenant (ADR 0067 Phase B). There is
 * no JWT on a Kafka consumer; {@link TenantContext#callAs} scopes the handler to the event's {@code
 * company_id} with the fixed {@code finance-consumer} actor so RLS + Auditable stamping apply — the
 * {@code StocktakeService} pattern. The transactional dedupe + posting lives in the proxied {@link
 * StockReceivedWriter} (separate bean; self-invocation would bypass the advice).
 */
@Service
public class StockReceivedService {

  private static final String CONSUMER_ACTOR = "finance-consumer";

  private final StockReceivedWriter writer;

  public StockReceivedService(StockReceivedWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded event under the event's tenant.
   *
   * @return true when this delivery performed the posting (or the claimed no-op); false when it was
   *     a re-delivery
   */
  public boolean handle(StockReceivedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.post(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.post throws only unchecked.
      throw new IllegalStateException("StockReceived handling failed", e);
    }
  }
}
