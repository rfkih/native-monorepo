package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCard;
import id.co.nativeapp.loyalty.giftcard.repository.GiftCardRepository;
import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntryType;
import id.co.nativeapp.loyalty.ledger.repository.GiftCardLedgerEntryRepository;
import id.co.nativeapp.loyalty.ledger.service.LoyaltyEventEmitter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work that applies one {@code GiftCardSold} event (ADR
 * 0027): creates the card (id FROM the event — never generated here) with a deterministically
 * derived {@code code}, or — if the card already exists (a top-up) — adds to its balance, then
 * appends a {@code LOAD} ledger entry and emits {@code GiftCardStateChanged}. A distinct bean from
 * {@link GiftCardSoldService} so the {@code @Transactional} advice and the RLS auto-apply aspect
 * engage through the Spring proxy.
 *
 * <p><strong>Simplification (documented).</strong> A LOAD is applied regardless of the card's
 * current state (even {@code VOID}/{@code EXPIRED}) — guarding against topping up a voided card is
 * out of scope for this wave (no producer path exists yet that would trigger it; see the Phase-4
 * catalog note that no vertical write path populates the {@code GiftCardSold} producer/consumer
 * machinery yet).
 */
@Component
public class GiftCardSoldWriter {

  private final GiftCardRepository giftCardRepository;
  private final GiftCardLedgerEntryRepository ledgerRepository;
  private final LoyaltyEventEmitter eventEmitter;
  private final ProcessedEventStore processedEvents;

  public GiftCardSoldWriter(
      GiftCardRepository giftCardRepository,
      GiftCardLedgerEntryRepository ledgerRepository,
      LoyaltyEventEmitter eventEmitter,
      ProcessedEventStore processedEvents) {
    this.giftCardRepository = giftCardRepository;
    this.ledgerRepository = ledgerRepository;
    this.eventEmitter = eventEmitter;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the fact exactly once per {@code fact.eventId()}. Must be called inside a {@link
   * id.co.nativeapp.tenant.TenantContext TenantContext} scope bound to the event's {@code
   * company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(GiftCardSoldFact fact) {
    return processedEvents.processOnce(fact.eventId(), () -> ingest(fact));
  }

  private void ingest(GiftCardSoldFact fact) {
    GiftCard card = giftCardRepository.findById(fact.giftCardId()).orElse(null);
    if (card == null) {
      card =
          new GiftCard(
              fact.giftCardId(),
              fact.currency(),
              fact.amountMinor(),
              fact.occurredAt(),
              fact.businessId());
      card.setCompanyId(fact.companyId());
    } else {
      card.load(fact.amountMinor());
    }

    GiftCardLedgerEntry entry =
        new GiftCardLedgerEntry(
            card.getId(),
            GiftCardLedgerEntryType.LOAD,
            fact.amountMinor(),
            fact.currency(),
            null,
            fact.eventId());
    entry.setCompanyId(fact.companyId());
    ledgerRepository.save(entry);

    GiftCard saved = giftCardRepository.save(card);
    eventEmitter.emitGiftCardStateChanged(
        saved.getId(),
        fact.companyId(),
        saved.getState().name(),
        saved.getBalanceMinor(),
        saved.getCurrency(),
        saved.getBalanceSeq(),
        fact.occurredAt());
  }
}
