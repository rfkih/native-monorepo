package id.co.nativeapp.barbershop.payment.service;

import id.co.nativeapp.barbershop.payment.messaging.PaymentChargeSucceededEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code PaymentChargeSucceeded} (ADR 0045) to the barbershop
 * ticket it settles.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "barbershop-payment-charge-consumer"} actor
 * (which lands in the Auditable {@code created_by}/{@code updated_by} of any row the capture
 * writes), then delegates to the proxied {@link PaymentChargeSucceededWriter} so the
 * {@code @Transactional} advice and the auto-RLS aspect engage under that tenant. Mirrors {@code
 * entitlement.service.EntitlementProjectionService} EXACTLY (barbershop's established consumer
 * idiom, itself a faithful clone of carwash-service's). The transactional unit of work — dedupe +
 * the vertical/reference/state/amount checks + capture — lives in the writer (a separate bean so
 * the Spring proxy is not bypassed by self-invocation).
 */
@Service
public class PaymentChargeSucceededService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "barbershop-payment-charge-consumer";

  private final PaymentChargeSucceededWriter writer;

  public PaymentChargeSucceededService(PaymentChargeSucceededWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded {@code PaymentChargeSucceeded}, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean apply(PaymentChargeSucceededEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply PaymentChargeSucceeded event", e);
    }
  }
}
