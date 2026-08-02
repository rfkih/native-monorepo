package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates posting a consumed {@code PayrollLiabilitiesPosted} run's liability recognition
 * entry (ADR 0032, Track P phase P4) — the liability counterpart of {@link
 * LaborCostPostingService}/{@link PayrollReconciliationService}.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> finance is purely
 * downstream; there is no JWT on the consumer path. This service binds the tenant scope from the
 * event's {@code company_id} via {@link TenantContext#callAs} with the shared {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates to
 * the proxied {@link PayrollLiabilityWriter} so the {@code @Transactional} advice and the auto-RLS
 * aspect engage under that tenant. The transactional unit of work (dedupe + supersession + resolve
 * + post) lives in the writer (a separate bean so the Spring proxy is not bypassed by
 * self-invocation).
 */
@Service
public class PayrollLiabilityService {

  private final PayrollLiabilityWriter writer;

  public PayrollLiabilityService(PayrollLiabilityWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code PayrollLiabilitiesPosted}, idempotently. Binds the event's tenant
   * for the duration of the posting transaction so RLS applies.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(PayrollLiabilitiesPostedEvent event) {
    try {
      return TenantContext.callAs(
          event.companyId(), LaborCostPostingService.CONSUMER_ACTOR, () -> writer.post(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.post throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle PayrollLiabilitiesPosted", e);
    }
  }
}
