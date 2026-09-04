package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.restaurant.inventory.messaging.InventoryPurchaseRecordedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code InventoryPurchaseRecorded} event (ADR 0072).
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed consumer actor (which lands in the Auditable {@code
 * created_by}), then delegates to the proxied {@link InventoryPurchaseApplyWriter} so the
 * {@code @Transactional} advice and the auto-RLS aspect engage under that tenant. Mirrors {@code
 * PaymentChargeExpiredService}.
 */
@Service
public class InventoryPurchaseApplyService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "restaurant-inventory-purchase-consumer";

  private final InventoryPurchaseApplyWriter writer;

  public InventoryPurchaseApplyService(InventoryPurchaseApplyWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded event, exactly once per event id, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer
   */
  public boolean apply(InventoryPurchaseRecordedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply InventoryPurchaseRecorded", e);
    }
  }
}
