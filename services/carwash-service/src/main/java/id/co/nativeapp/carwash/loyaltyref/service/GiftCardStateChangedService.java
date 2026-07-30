package id.co.nativeapp.carwash.loyaltyref.service;

import id.co.nativeapp.carwash.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code GiftCardStateChanged} event to the local {@code
 * gift_card_ref} read model. Mirrors {@link LoyaltyBalanceChangedService}.
 */
@Service
public class GiftCardStateChangedService {

  public static final String CONSUMER_ACTOR = "gift-card-state-consumer";

  private final GiftCardStateChangedWriter writer;

  public GiftCardStateChangedService(GiftCardStateChangedWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies the event to {@code gift_card_ref}, exactly once per event id.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  public boolean apply(GiftCardStateChangedEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.apply(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected checked exception from callAs", e);
    }
  }
}
