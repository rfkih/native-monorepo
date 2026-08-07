package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code PaymentChargeSucceeded} event (ADR 0045).
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "restaurant-payment-charge-consumer"} actor
 * (which lands in the Auditable {@code created_by}), then delegates to the proxied {@link
 * PaymentChargeSucceededWriter} so the {@code @Transactional} advice and the auto-RLS aspect engage
 * under that tenant. The transactional unit of work — dedupe + verify + capture-or-park — lives in
 * the writer (a separate bean so the Spring proxy is not bypassed by self-invocation).
 *
 * <p>Mirrors {@code loyaltyref.service.GiftCardStateChangedService} / {@code
 * entitlement.service.EntitlementProjectionService} in shape.
 */
@Service
public class PaymentChargeSucceededService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "restaurant-payment-charge-consumer";

  private final PaymentChargeSucceededWriter writer;

  public PaymentChargeSucceededService(PaymentChargeSucceededWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded {@code PaymentChargeSucceeded} event, exactly once per event id, in the
   * event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer
   */
  public boolean apply(PaymentChargeSucceededEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply PaymentChargeSucceeded", e);
    }
  }
}
