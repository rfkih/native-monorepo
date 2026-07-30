package id.co.nativeapp.finance.giftcard.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.giftcard.messaging.GiftCardSoldEvent;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts a consumed {@code GiftCardSold} to
 * the GL as a LIABILITY entry — idempotently (ADR 0027, Phase 4 of the POS-parity program).
 *
 * <p>It is a distinct bean (not a private method on {@link GiftCardPostingService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC, and
 * the tenant GUC is exactly what makes the RLS {@code WITH CHECK} pass on the inserts (rule 5). The
 * caller ({@link GiftCardPostingService}) binds the tenant from the event's {@code company_id}
 * before invoking this method. Mirrors {@code RevenuePostingWriter}'s journal-posting mechanics.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> {@link ProcessedEventStore#processOnce} claims
 * {@code gift_card_sale_id} inside this transaction, so the dedupe claim and the journal entry
 * commit (or roll back) together; only the FIRST delivery posts. The {@code journal_entry
 * source_event_id UNIQUE} constraint is the database backstop behind this — a re-delivered {@code
 * GiftCardSold} (same {@code gift_card_sale_id}) can never produce a second entry.
 *
 * <p><strong>A gift-card sale is a LIABILITY, not revenue (ADR 0027 decision 4) — NO read-model
 * update.</strong> Unlike {@code RevenuePostingWriter}, this writer does NOT touch {@code
 * ledger_posting} (the dimensional REVENUE/EXPENSE read-model source), {@code
 * consolidated_revenue}, {@code outlet_revenue}, or {@code consolidated_pnl} — a card sold today is
 * not income today; the company merely now owes the bearer stored value. Revenue is recognised
 * later, at redemption, when {@code SaleRecorded.gift_card_redeemed_minor} is folded into the SALE
 * v3 template's {@code NET_TENDER}/{@code GIFT_CARD_TENDER} split ({@code RevenuePostingWriter}).
 * This writer posts ONLY the double-entry GL journal entry (Dr &lt;tender clearing&gt; / Cr {@link
 * AccountRole#GIFT_CARD_LIABILITY}, the {@code GIFT_CARD_SALE} v1 template, V37).
 */
@Component
public class GiftCardPostingWriter {

  private final ProcessedEventStore processedEvents;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  public GiftCardPostingWriter(
      ProcessedEventStore processedEvents,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.processedEvents = processedEvents;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Posts the event's gift-card liability entry to the GL, exactly once per {@code
   * gift_card_sale_id}. Must be called inside a {@link TenantContext} scope bound to the event's
   * {@code company_id} (the auto-RLS aspect sets the tenant GUC for this transaction from that
   * scope).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean post(GiftCardSoldEvent event) {
    return processedEvents.processOnce(event.giftCardSaleId(), () -> postGiftCardSale(event));
  }

  private void postGiftCardSale(GiftCardSoldEvent event) {
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Double-entry GL journal ONLY (no dimensional ledger_posting, no read-model accumulation —
    // see the class javadoc). The GIFT_CARD_SALE v1 template (V37) is Dr <tender clearing> (GROSS)
    // / Cr GIFT_CARD_LIABILITY (GROSS) — both lines resolve via the existing single-arg GROSS basis
    // (JournalPostingService#buildEntry), so no new amount_basis handling is needed here. The
    // clearing account is routed by tender type, the SAME override mechanism SaleRecorded uses
    // (ADR 0006 slice 2) — this template line simply opts into it (V37 header).
    AccountRole clearingRole = resolveClearingRole(event.tenderType());
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.GIFT_CARD_SALE,
            period,
            event.amount(),
            event.occurredAt(),
            event.giftCardSaleId(),
            "GiftCardSold",
            false,
            clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
    glEntry.setCompanyId(companyId);
    // saveAndFlush flushes the journal_entry INSERT to Postgres immediately so the FK on
    // journal_line.entry_id is satisfied when the line INSERTs follow in the same transaction.
    journalEntryRepository.saveAndFlush(glEntry);
    glEntry
        .getLines()
        .forEach(
            line -> {
              line.setCompanyId(companyId);
              journalLineRepository.save(line);
            });
  }

  /**
   * Resolves the GL clearing {@link AccountRole} from a {@code tender_type} wire value — the SAME
   * logic as {@code RevenuePostingWriter.resolveClearingRole} / {@code
   * ReversalPostingWriter.resolveClearingRole}, inlined here to avoid cross-feature coupling (the
   * established precedent those two already follow for the identical duplication). Returns {@link
   * AccountRole#CASH_CLEARING} for {@code null} (legacy / no-tender) and for {@code "CASH"}; {@link
   * AccountRole#QRIS_CLEARING} for {@code "QRIS"}; {@link AccountRole#CARD_CLEARING} for {@code
   * "CARD"}. Any unrecognised value falls back to {@link AccountRole#CASH_CLEARING} (safe default —
   * money is never dropped).
   */
  static AccountRole resolveClearingRole(String tenderType) {
    if (tenderType == null) {
      return AccountRole.CASH_CLEARING;
    }
    return switch (tenderType) {
      case "CASH" -> AccountRole.CASH_CLEARING;
      case "QRIS" -> AccountRole.QRIS_CLEARING;
      case "CARD" -> AccountRole.CARD_CLEARING;
      default -> AccountRole.CASH_CLEARING;
    };
  }
}
