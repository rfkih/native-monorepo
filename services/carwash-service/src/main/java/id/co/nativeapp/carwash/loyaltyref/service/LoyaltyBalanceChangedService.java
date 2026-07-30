package id.co.nativeapp.carwash.loyaltyref.service;

import id.co.nativeapp.carwash.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code LoyaltyBalanceChanged} event to the local {@code
 * member_balance_ref} read model. Not {@code @Transactional} itself — the transactional boundary
 * lives on {@link LoyaltyBalanceChangedWriter} (the {@code *Writer} pattern). Binds the tenant from
 * the event's {@code company_id} (no JWT on the consumer path) before calling the writer, so the
 * auto-RLS aspect sets {@code app.current_tenant} on the writer's transaction (rule 5).
 *
 * <p>Mirrors {@code outletref.service.UserOutletAssignmentRefService}.
 */
@Service
public class LoyaltyBalanceChangedService {

  public static final String CONSUMER_ACTOR = "loyalty-balance-consumer";

  private final LoyaltyBalanceChangedWriter writer;

  public LoyaltyBalanceChangedService(LoyaltyBalanceChangedWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies the event to {@code member_balance_ref}, exactly once per event id.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  public boolean apply(LoyaltyBalanceChangedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected checked exception from callAs", e);
    }
  }
}
