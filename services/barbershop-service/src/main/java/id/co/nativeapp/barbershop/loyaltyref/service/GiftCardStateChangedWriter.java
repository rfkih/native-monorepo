package id.co.nativeapp.barbershop.loyaltyref.service;

import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.repository.GiftCardRefRepository;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that set-if-newer upserts a consumed {@code
 * GiftCardStateChanged} event into {@code gift_card_ref} — idempotently. Mirrors {@link
 * LoyaltyBalanceChangedWriter}.
 */
@Component
public class GiftCardStateChangedWriter {

  private final ProcessedEventStore processedEvents;
  private final GiftCardRefRepository repository;

  public GiftCardStateChangedWriter(
      ProcessedEventStore processedEvents, GiftCardRefRepository repository) {
    this.processedEvents = processedEvents;
    this.repository = repository;
  }

  /**
   * Applies the event to {@code gift_card_ref}, exactly once per event id. Must be called inside a
   * {@link TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(GiftCardStateChangedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(GiftCardStateChangedEvent event) {
    String companyId = TenantContext.require().companyId();
    repository.upsertSetIfNewer(
        event.giftCardId(),
        companyId,
        event.state(),
        event.balanceMinor(),
        event.currency(),
        event.balanceSeq(),
        GiftCardStateChangedService.CONSUMER_ACTOR);
  }
}
