package id.co.nativeapp.finance.labor;

import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates posting a consumed {@code LaborCostAllocated} bucket to the dimensional ledger (#23)
 * — the labor counterpart of {@code ExpensePostingService}.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> finance is purely
 * downstream; there is no JWT on the consumer path. This service binds the tenant scope from the
 * event's {@code company_id} via {@link TenantContext#callAs} with a fixed {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates to
 * the proxied {@link LaborCostPostingWriter} so the {@code @Transactional} advice and the auto-RLS
 * aspect engage under that tenant. The transactional unit of work (dedupe + reconcile-accumulate +
 * supersession + resolve + post + P&amp;L) lives in the writer (a separate bean so the Spring proxy
 * is not bypassed by self-invocation).
 */
@Service
public class LaborCostPostingService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final LaborCostPostingWriter writer;

  public LaborCostPostingService(LaborCostPostingWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code LaborCostAllocated} bucket, idempotently. Binds the event's tenant
   * for the duration of the posting transaction so RLS applies.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(LaborCostAllocatedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.post(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.post throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle LaborCostAllocated", e);
    }
  }
}
