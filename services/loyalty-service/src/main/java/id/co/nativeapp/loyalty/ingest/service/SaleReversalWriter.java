package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCard;
import id.co.nativeapp.loyalty.giftcard.repository.GiftCardRepository;
import id.co.nativeapp.loyalty.ingest.domain.PendingSaleReversal;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.loyalty.ingest.repository.PendingSaleReversalRepository;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntryType;
import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntryType;
import id.co.nativeapp.loyalty.ledger.repository.GiftCardLedgerEntryRepository;
import id.co.nativeapp.loyalty.ledger.repository.LoyaltyLedgerEntryRepository;
import id.co.nativeapp.loyalty.ledger.service.LoyaltyEventEmitter;
import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import id.co.nativeapp.loyalty.member.repository.LoyaltyMemberRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work that REVERSES a sale's earn/redeem entries by {@code
 * sale_id} (ADR 0027) — full-reversal only, mirroring finance's posture (loyalty stores only
 * SALE-LEVEL facts, not a per-line/per-refund breakdown, so a {@code SaleVoided} or a PARTIAL
 * {@code SaleRefunded} both reverse the FULL loyalty impact of the original sale). A distinct bean
 * from {@link SaleReversalService} so the {@code @Transactional} advice and the RLS auto-apply
 * aspect engage through the Spring proxy.
 *
 * <p><strong>Net-reversal design (documented — how this satisfies the ledger's idempotency
 * backstop).</strong> {@code UNIQUE(company_id, source_event_id, entry_type)} allows at most ONE
 * {@code REVERSE} row per (ledger table, causing event) — so rather than writing one reversal row
 * per original entry (which could collide when a sale had BOTH an {@code EARN} and a {@code REDEEM}
 * entry, since both reversals would share the same causing event id and {@code REVERSE} type), this
 * SUMS every non-reversed {@code EARN}/{@code REDEEM} entry for the sale into ONE net {@code
 * REVERSE} row per ledger table. The reversed row's {@code points_delta}/{@code amount_minor} is
 * the NEGATION of that sum — applying it to the member/card balance exactly cancels the sale's
 * original net effect, whether that effect came from one entry or several.
 *
 * <p><strong>Cross-topic reorder (QA sweep 2026-08-05, CRITICAL fix).</strong> {@code
 * SaleVoided}/{@code SaleRefunded} and {@code SaleRecorded} travel on SEPARATE topics, so a
 * reversal can be consumed BEFORE its sale was ingested. When NEITHER ledger has any trace of the
 * sale, this writer no longer returns silently (which lost the reversal forever — the event id was
 * still claimed): it PARKS one durable {@link PendingSaleReversal} row and completes; {@link
 * SaleIngestWriter} applies the parked reversal in the same transaction that ingests the sale.
 * Parking (not throwing) is deliberate: throwing would block the whole partition behind one missing
 * sale, and a DLT would need manual replay.
 */
@Component
public class SaleReversalWriter {

  private static final List<LoyaltyLedgerEntryType> REVERSIBLE_LOYALTY_TYPES =
      List.of(LoyaltyLedgerEntryType.EARN, LoyaltyLedgerEntryType.REDEEM);

  private static final List<GiftCardLedgerEntryType> REVERSIBLE_GIFT_CARD_TYPES =
      List.of(GiftCardLedgerEntryType.REDEEM);

  private final LoyaltyMemberRepository memberRepository;
  private final GiftCardRepository giftCardRepository;
  private final LoyaltyLedgerEntryRepository ledgerRepository;
  private final GiftCardLedgerEntryRepository giftCardLedgerRepository;
  private final PendingSaleReversalRepository pendingReversalRepository;
  private final LoyaltyEventEmitter eventEmitter;
  private final ProcessedEventStore processedEvents;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SaleReversalWriter(
      LoyaltyMemberRepository memberRepository,
      GiftCardRepository giftCardRepository,
      LoyaltyLedgerEntryRepository ledgerRepository,
      GiftCardLedgerEntryRepository giftCardLedgerRepository,
      PendingSaleReversalRepository pendingReversalRepository,
      LoyaltyEventEmitter eventEmitter,
      ProcessedEventStore processedEvents) {
    this.memberRepository = memberRepository;
    this.giftCardRepository = giftCardRepository;
    this.ledgerRepository = ledgerRepository;
    this.giftCardLedgerRepository = giftCardLedgerRepository;
    this.pendingReversalRepository = pendingReversalRepository;
    this.eventEmitter = eventEmitter;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the reversal exactly once per {@code fact.eventId()}. Must be called inside a {@link
   * id.co.nativeapp.tenant.TenantContext TenantContext} scope bound to the event's {@code
   * company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(SaleReversalFact fact) {
    return processedEvents.processOnce(fact.eventId(), () -> reverse(fact));
  }

  private void reverse(SaleReversalFact fact) {
    boolean loyaltySeen =
        reverseLoyaltyLedger(fact.saleId(), fact.eventId(), fact.companyId(), fact.occurredAt());
    boolean giftCardSeen =
        reverseGiftCardLedger(fact.saleId(), fact.eventId(), fact.companyId(), fact.occurredAt());
    if (loyaltySeen || giftCardSeen) {
      return;
    }
    // NEITHER ledger has any trace of this sale: either it carried no loyalty facts, or its
    // SaleRecorded has not been consumed yet (cross-topic reorder) — indistinguishable right now.
    // Park durably (first reversal event wins — review S2); SaleIngestWriter applies it if/when
    // the sale arrives. Never silently drop: this event id is claimed in this same transaction.
    pendingReversalRepository.parkIfAbsent(
        UUID.randomUUID(),
        fact.saleId(),
        fact.eventId(),
        fact.occurredAt(),
        TenantContext.require().actor(),
        fact.companyId());
  }

  /**
   * Applies a parked reversal inside the CALLER's (ingest) transaction — the parked event id was
   * already claimed when the row was parked, so this deliberately bypasses {@code processOnce}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void applyParked(PendingSaleReversal parked) {
    reverseLoyaltyLedger(
        parked.getSaleId(),
        parked.getReversalEventId(),
        parked.getCompanyId(),
        parked.getReversalOccurredAt());
    reverseGiftCardLedger(
        parked.getSaleId(),
        parked.getReversalEventId(),
        parked.getCompanyId(),
        parked.getReversalOccurredAt());
  }

  /**
   * @return {@code true} when the sale left ANY trace in this ledger (reversible entries, or an
   *     existing REVERSE row) — i.e. the sale has definitely been ingested; {@code false} when the
   *     ledger has never seen it (the caller decides whether to park)
   */
  private boolean reverseLoyaltyLedger(
      UUID saleId, UUID sourceEventId, String companyId, Instant occurredAt) {
    // Self-enforcing full-reversal-only invariant (review S2): two DISTINCT reversal events for
    // one sale (a SaleVoided followed by a SaleRefunded) must not each re-reverse the originals
    // and double-credit — processOnce dedupes only a REDELIVERY of the same event.
    if (ledgerRepository.existsBySaleIdAndEntryType(saleId, LoyaltyLedgerEntryType.REVERSE)) {
      return true;
    }
    List<LoyaltyLedgerEntry> entries =
        ledgerRepository.findBySaleIdAndEntryTypeIn(saleId, REVERSIBLE_LOYALTY_TYPES);
    if (entries.isEmpty()) {
      return false;
    }
    long netPointsDelta = entries.stream().mapToLong(LoyaltyLedgerEntry::getPointsDelta).sum();
    if (netPointsDelta == 0L) {
      return true;
    }
    LoyaltyMember member = memberRepository.findById(entries.get(0).getMemberId()).orElse(null);
    if (member == null) {
      return true;
    }

    // value_minor/currency are left null on the REVERSE row: EARN's value_minor (the taxable base)
    // and REDEEM's value_minor (the redeemed currency amount) are not commensurable, so a summed
    // "value" would not have a single clean meaning. points_delta alone is well-defined.
    LoyaltyLedgerEntry reversal =
        new LoyaltyLedgerEntry(
            member.getId(),
            LoyaltyLedgerEntryType.REVERSE,
            -netPointsDelta,
            null,
            null,
            saleId,
            sourceEventId);
    reversal.setCompanyId(companyId);
    ledgerRepository.save(reversal);

    long resultingBalance = member.applyPointsDelta(-netPointsDelta);
    memberRepository.save(member);
    eventEmitter.emitBalanceChanged(
        member.getId(),
        companyId,
        resultingBalance,
        member.getBalanceSeq(),
        "REVERSED",
        occurredAt);
    return true;
  }

  /**
   * @return {@code true} when the sale left ANY trace in this ledger — see the loyalty twin
   */
  private boolean reverseGiftCardLedger(
      UUID saleId, UUID sourceEventId, String companyId, Instant occurredAt) {
    // Same self-enforcing already-reversed guard as the loyalty twin (review S2).
    if (giftCardLedgerRepository.existsBySaleIdAndEntryType(
        saleId, GiftCardLedgerEntryType.REVERSE)) {
      return true;
    }
    List<GiftCardLedgerEntry> entries =
        giftCardLedgerRepository.findBySaleIdAndEntryTypeIn(saleId, REVERSIBLE_GIFT_CARD_TYPES);
    if (entries.isEmpty()) {
      return false;
    }
    long netAmountMinor = entries.stream().mapToLong(GiftCardLedgerEntry::getAmountMinor).sum();
    if (netAmountMinor == 0L) {
      return true;
    }
    GiftCard card = giftCardRepository.findById(entries.get(0).getGiftCardId()).orElse(null);
    if (card == null) {
      return true;
    }

    GiftCardLedgerEntry reversal =
        new GiftCardLedgerEntry(
            card.getId(),
            GiftCardLedgerEntryType.REVERSE,
            -netAmountMinor,
            card.getCurrency(),
            saleId,
            sourceEventId);
    reversal.setCompanyId(companyId);
    giftCardLedgerRepository.save(reversal);

    long resultingBalance = card.applyBalanceDelta(-netAmountMinor);
    giftCardRepository.save(card);
    eventEmitter.emitGiftCardStateChanged(
        card.getId(),
        companyId,
        card.getState().name(),
        resultingBalance,
        card.getCurrency(),
        card.getBalanceSeq(),
        occurredAt);
    return true;
  }
}
