package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Binds the tenant from a consumed {@code MetricPublished}'s {@code company_id} (no JWT on the
 * consumer path) and delegates to the proxied {@link MetricInputProjectionWriter} so the
 * {@code @Transactional} advice + auto-RLS aspect engage under that tenant.
 */
@Service
public class MetricInputProjectionService {

  /** The audit actor stamped into {@code created_by} for system-driven consumer writes. */
  public static final String CONSUMER_ACTOR = "employee-metric-consumer";

  private final MetricInputProjectionWriter writer;

  public MetricInputProjectionService(MetricInputProjectionWriter writer) {
    this.writer = writer;
  }

  /**
   * @return {@code true} if applied (first delivery), {@code false} if a skipped re-delivery.
   */
  public boolean apply(MetricProjectedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to apply MetricPublished event", e);
    }
  }
}
