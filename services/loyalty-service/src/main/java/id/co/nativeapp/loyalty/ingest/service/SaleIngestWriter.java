package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.loyalty.earnrule.projection.EarnRuleView;
import id.co.nativeapp.loyalty.earnrule.repository.EarnRuleRepository;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCard;
import id.co.nativeapp.loyalty.giftcard.repository.GiftCardRepository;
import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntryType;
import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntryType;
import id.co.nativeapp.loyalty.ledger.repository.GiftCardLedgerEntryRepository;
import id.co.nativeapp.loyalty.ledger.repository.LoyaltyLedgerEntryRepository;
import id.co.nativeapp.loyalty.ledger.service.LoyaltyEventEmitter;
import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import id.co.nativeapp.loyalty.member.repository.LoyaltyMemberRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work that applies one {@code SaleRecorded} event's
 * loyalty/gift-card facts (ADR 0027) — points EARN/REDEEM and gift-card REDEEM — idempotently. A
 * distinct bean from {@link SaleIngestService} so the {@code @Transactional} advice and the RLS
 * auto-apply aspect engage through the Spring proxy (the {@code StaffProjectionWriter} pattern);
 * the caller binds the tenant from the event's {@code company_id} before invoking this method.
 *
 * <p><strong>Processing order (within the one committed transaction):</strong>
 *
 * <ol>
 *   <li><strong>Points REDEEM</strong> — when {@code loyaltyMemberId} + a positive {@code
 *       loyaltyRedeemedPoints} are present: appends a {@code REDEEM} ledger entry, applies the
 *       (possibly negative-going) delta to the member, emits {@code LoyaltyBalanceChanged}, and —
 *       if the resulting balance is negative — emits {@code LoyaltyRedemptionFlagged} (the
 *       eventual-consistency overdraft: a vertical accepted the redemption against its stale local
 *       cache; the ledger applies anyway, never silently and never blocking, rule 2).
 *   <li><strong>Gift-card REDEEM</strong> — the identical shape, against the {@code gift_card}
 *       aggregate: {@code balance -= gift_card_redeemed_minor}; state becomes {@code DEPLETED}
 *       exactly at zero, stays {@code ACTIVE} otherwise (even negative); overdraft flag on a
 *       negative result.
 *   <li><strong>Points EARN</strong> — ALWAYS applied when {@code loyaltyMemberId} is present AND
 *       an effective {@code earn_rule} resolves for the sale's date: {@code base = subtotal -
 *       discount - loyaltyRedeemedMinor} (a {@code null subtotal} falls back to {@code
 *       amount_minor}, the SAME convention finance already uses for a legacy null breakdown — see
 *       docs/EVENT-CATALOG.md {@code SaleRecorded.subtotal_minor}; {@code null} discount/redeemed
 *       treated as 0). If {@code minSaleMinor} is configured and {@code base} does not meet it, NO
 *       earn entry is written at all (not even a zero-point one) — this is the floor's whole
 *       purpose. Otherwise {@code points = floor(base * pointsPerMinorBp / 10000)}; a
 *       zero-or-negative computed points value also writes nothing (an already-idempotent no-op).
 * </ol>
 *
 * <p>Redeem happens BEFORE earn so a sale that both redeems and earns in the same transaction sees
 * the ALREADY-REDUCED {@code loyaltyRedeemedMinor} correctly subtracted from the earn base (the
 * base formula uses the event's own {@code loyaltyRedeemedMinor} field directly, not the member's
 * mutated balance, so the ordering does not actually change the earn math — it is sequenced this
 * way purely so the ledger reads chronologically: redeem-then-earn, matching how a real checkout
 * applies a discount before computing the residual reward).
 */
@Component
public class SaleIngestWriter {

  private final LoyaltyMemberRepository memberRepository;
  private final GiftCardRepository giftCardRepository;
  private final EarnRuleRepository earnRuleRepository;
  private final LoyaltyLedgerEntryRepository ledgerRepository;
  private final GiftCardLedgerEntryRepository giftCardLedgerRepository;
  private final LoyaltyEventEmitter eventEmitter;
  private final ProcessedEventStore processedEvents;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SaleIngestWriter(
      LoyaltyMemberRepository memberRepository,
      GiftCardRepository giftCardRepository,
      EarnRuleRepository earnRuleRepository,
      LoyaltyLedgerEntryRepository ledgerRepository,
      GiftCardLedgerEntryRepository giftCardLedgerRepository,
      LoyaltyEventEmitter eventEmitter,
      ProcessedEventStore processedEvents) {
    this.memberRepository = memberRepository;
    this.giftCardRepository = giftCardRepository;
    this.earnRuleRepository = earnRuleRepository;
    this.ledgerRepository = ledgerRepository;
    this.giftCardLedgerRepository = giftCardLedgerRepository;
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
  public boolean apply(SaleRecordedFact fact) {
    return processedEvents.processOnce(fact.eventId(), () -> ingest(fact));
  }

  private void ingest(SaleRecordedFact fact) {
    LoyaltyMember member =
        fact.loyaltyMemberId() == null
            ? null
            : memberRepository.findById(fact.loyaltyMemberId()).orElse(null);

    if (member != null && positive(fact.loyaltyRedeemedPoints())) {
      redeemPoints(member, fact);
    }

    if (fact.giftCardId() != null && positive(fact.giftCardRedeemedMinor())) {
      redeemGiftCard(fact);
    }

    if (member != null) {
      earnPoints(member, fact);
    }
  }

  private void redeemPoints(LoyaltyMember member, SaleRecordedFact fact) {
    long redeemPoints = fact.loyaltyRedeemedPoints();
    LoyaltyLedgerEntry entry =
        new LoyaltyLedgerEntry(
            member.getId(),
            LoyaltyLedgerEntryType.REDEEM,
            -redeemPoints,
            fact.loyaltyRedeemedMinor(),
            fact.currency(),
            fact.saleId(),
            fact.eventId());
    entry.setCompanyId(fact.companyId());
    ledgerRepository.save(entry);

    long resultingBalance = member.applyPointsDelta(-redeemPoints);
    memberRepository.save(member);
    eventEmitter.emitBalanceChanged(
        member.getId(),
        fact.companyId(),
        resultingBalance,
        member.getBalanceSeq(),
        "REDEEMED",
        fact.occurredAt());

    if (resultingBalance < 0L) {
      eventEmitter.emitPointsOverdraftFlagged(
          fact.companyId(),
          fact.businessId(),
          fact.saleId(),
          member.getId(),
          -resultingBalance,
          fact.occurredAt());
    }
  }

  private void redeemGiftCard(SaleRecordedFact fact) {
    GiftCard card = giftCardRepository.findById(fact.giftCardId()).orElse(null);
    if (card == null) {
      // The card doesn't exist yet in this ledger (e.g. GiftCardSold has not yet been consumed) —
      // skip silently rather than fabricate a card from a redemption event. A future GiftCardSold
      // delivery would then create a card with the wrong balance history; this is a signed-off gap
      // (the redemption event itself is still marked processed, so no infinite retry).
      return;
    }
    long redeemMinor = fact.giftCardRedeemedMinor();
    GiftCardLedgerEntry entry =
        new GiftCardLedgerEntry(
            card.getId(),
            GiftCardLedgerEntryType.REDEEM,
            -redeemMinor,
            card.getCurrency(),
            fact.saleId(),
            fact.eventId());
    entry.setCompanyId(fact.companyId());
    giftCardLedgerRepository.save(entry);

    long resultingBalance = card.applyBalanceDelta(-redeemMinor);
    giftCardRepository.save(card);
    eventEmitter.emitGiftCardStateChanged(
        card.getId(),
        fact.companyId(),
        card.getState().name(),
        resultingBalance,
        card.getCurrency(),
        card.getBalanceSeq(),
        fact.occurredAt());

    if (resultingBalance < 0L) {
      eventEmitter.emitGiftCardOverdraftFlagged(
          fact.companyId(),
          fact.businessId(),
          fact.saleId(),
          card.getId(),
          -resultingBalance,
          fact.occurredAt());
    }
  }

  private void earnPoints(LoyaltyMember member, SaleRecordedFact fact) {
    LocalDate asOf = fact.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
    Optional<EarnRuleView> ruleOpt = earnRuleRepository.findEffective(asOf);
    if (ruleOpt.isEmpty()) {
      return;
    }
    EarnRuleView rule = ruleOpt.get();

    long subtotal = fact.subtotalMinor() != null ? fact.subtotalMinor() : fact.amountMinor();
    long discount = fact.discountMinor() != null ? fact.discountMinor() : 0L;
    long redeemedMinor = fact.loyaltyRedeemedMinor() != null ? fact.loyaltyRedeemedMinor() : 0L;
    long base = subtotal - discount - redeemedMinor;

    if (base <= 0L) {
      return;
    }
    Long minSaleMinor = rule.getMinSaleMinor();
    if (minSaleMinor != null && base < minSaleMinor) {
      return;
    }

    long points = Math.floorDiv(Math.multiplyExact(base, rule.getPointsPerMinorBp()), 10_000L);
    if (points <= 0L) {
      return;
    }

    LoyaltyLedgerEntry entry =
        new LoyaltyLedgerEntry(
            member.getId(),
            LoyaltyLedgerEntryType.EARN,
            points,
            base,
            fact.currency(),
            fact.saleId(),
            fact.eventId());
    entry.setCompanyId(fact.companyId());
    ledgerRepository.save(entry);

    long resultingBalance = member.applyPointsDelta(points);
    memberRepository.save(member);
    eventEmitter.emitBalanceChanged(
        member.getId(),
        fact.companyId(),
        resultingBalance,
        member.getBalanceSeq(),
        "EARNED",
        fact.occurredAt());
  }

  private static boolean positive(Long value) {
    return value != null && value > 0L;
  }
}
