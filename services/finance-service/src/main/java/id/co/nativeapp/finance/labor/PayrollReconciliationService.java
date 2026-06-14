package id.co.nativeapp.finance.labor;

import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates reconciling a consumed {@code PayrollPosted} run against the sum of its {@code
 * LaborCostAllocated} buckets (#23) — the run-level control counterpart of {@link
 * LaborCostPostingService}.
 *
 * <p>Binds the event's {@code company_id} as the tenant (the inbound tenant comes from the event,
 * never a request) via {@link TenantContext#callAs} so the auto-RLS aspect engages, then delegates
 * to the proxied {@link PayrollReconciliationWriter} where the {@code @Transactional} unit of work
 * lives (a separate bean so the Spring proxy is not bypassed by self-invocation).
 */
@Service
public class PayrollReconciliationService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final PayrollReconciliationWriter writer;

  public PayrollReconciliationService(PayrollReconciliationWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code PayrollPosted}, idempotently. Binds the event's tenant for the
   * reconciliation transaction so RLS applies.
   *
   * @return {@code true} if this delivery reconciled (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(PayrollPostedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.reconcile(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.reconcile throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle PayrollPosted", e);
    }
  }
}
