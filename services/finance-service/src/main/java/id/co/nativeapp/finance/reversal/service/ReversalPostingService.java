package id.co.nativeapp.finance.reversal.service;

import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates posting a consumed {@code SaleVoided} or {@code SaleRefunded} to the ledger — the
 * consumer-side reversal core (ADR 0006, slice 4).
 *
 * <p>The inbound tenant comes from the event, not a request. This service binds the tenant scope
 * from the event's {@code company_id} via {@link TenantContext#callAs} with a fixed {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates to
 * the proxied {@link ReversalPostingWriter} so the {@code @Transactional} advice and the auto-RLS
 * aspect engage under that tenant. Follows the exact same pattern as {@code RevenuePostingService}.
 */
@Service
public class ReversalPostingService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final ReversalPostingWriter writer;

  public ReversalPostingService(ReversalPostingWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code SaleVoided}, idempotently. Binds the event's tenant for the duration
   * of the reversal transaction so RLS applies.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if re-delivery
   */
  public boolean handleVoid(SaleVoidedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.postVoid(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle SaleVoided", e);
    }
  }

  /**
   * Handles one decoded {@code SaleRefunded}, idempotently. Binds the event's tenant for the
   * duration of the reversal transaction so RLS applies.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if re-delivery
   */
  public boolean handleRefund(SaleRefundedEvent event) {
    try {
      return TenantContext.callAs(
          event.companyId(), CONSUMER_ACTOR, () -> writer.postRefund(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle SaleRefunded", e);
    }
  }
}
