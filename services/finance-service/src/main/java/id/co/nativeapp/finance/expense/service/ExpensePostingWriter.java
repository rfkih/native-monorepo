package id.co.nativeapp.finance.expense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts a consumed {@code ExpenseRecorded}
 * to the dimensional ledger as an EXPENSE posting and accumulates the P&amp;L read model's expense
 * leg — idempotently. The expense counterpart of {@code RevenuePostingWriter}.
 *
 * <p>It is a distinct bean (not a private method on {@link ExpensePostingService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC — and
 * the tenant GUC is exactly what makes the RLS {@code WITH CHECK} pass on the inserts (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Everything below happens in ONE transaction: the
 * {@link ProcessedEventStore#processOnce} dedupe claim and the side effects commit (or roll back)
 * together. A re-delivered {@code ExpenseRecorded} (same event id) is a clean no-op — no second
 * ledger posting, the P&amp;L is not double-counted. The {@code source_event_id UNIQUE} constraint
 * on {@code ledger_posting} is the database backstop.
 *
 * <p><strong>Resolution + fail-safe.</strong> The EXPENSE gl_account is resolved on write from the
 * event's {@code gl_hint} via the versioned, effective-dated {@code mapping_rule} ({@link
 * GlAccountResolver#resolveExpense}). An unmappable hint does NOT drop the money: it FAILS SAFE to
 * the suspense account, and the posting still lands (HR-3 — money is never silently lost). The
 * unmapped case is logged for observability.
 */
@Component
public class ExpensePostingWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpensePostingWriter.class);

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ExpensePostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Posts the event's expense to the ledger and accumulates the P&amp;L read model, exactly once
   * per event id. Must be called inside a {@link TenantContext} scope bound to the event's {@code
   * company_id} (the auto-RLS aspect sets the tenant GUC for this transaction from that scope).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean post(ExpenseRecordedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postExpense(event));
  }

  private void postExpense(ExpenseRecordedEvent event) {
    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // 0) GUARD the single-base-currency invariant (#26): an expense whose currency diverges from
    // the
    //    period's already-established currency violates the company's immutable base currency (a
    //    producer sent the wrong currency). Fail closed BEFORE writing anything — the consume
    //    transaction rolls back and the record is DLT'd — rather than silently creating a divergent
    //    second-currency read-model row that detonates later as a read-time 500.
    pnlReadModel.requireConsistentCurrency(period, amount);

    // 1) RESOLVE the EXPENSE gl_account via the versioned, effective-dated mapping_rule from the
    //    gl_hint (CQRS: resolve on write). An unmappable hint fails safe to the suspense account —
    //    the money is still posted, never dropped (HR-3).
    GlAccountResolution resolution =
        glAccountResolver.resolveExpense(event.glHint(), event.occurredAt());
    if (!resolution.mapped()) {
      log.warn(
          "Unmappable expense gl_hint='{}' (eventId={}); posting to suspense account {} — money is"
              + " preserved on the books, not dropped",
          event.glHint(),
          event.eventId(),
          resolution.accountCode());
    }

    // 2) Append the immutable, dimensional EXPENSE ledger posting. source_event_id is UNIQUE.
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.businessId(),
            period,
            amount,
            resolution.accountCode(),
            event.eventId());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 3) Atomically accumulate the P&L read model's EXPENSE leg in the SAME transaction (the
    //    no-read-modify-write upsert), so concurrent distinct expenses for the same key never lose
    //    an update and the P&L net (revenue - expense) reflects this expense.
    pnlReadModel.addExpense(period, amount, companyId, actor);

    // 4) Double-entry GL journal — SAME transaction, SAME processOnce claim. Build a balanced
    //    journal entry from the illustrative posting template (EXPENSE: Dr EXPENSE / Cr
    // CASH_CLEARING)
    //    and save it alongside the dimensional posting. source_event_id UNIQUE on journal_entry is
    //    the DB backstop — re-deliveries are already blocked by processOnce above.
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.EXPENSE,
            period,
            amount,
            event.occurredAt(),
            event.eventId(),
            "ExpenseRecorded",
            false);
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
}
