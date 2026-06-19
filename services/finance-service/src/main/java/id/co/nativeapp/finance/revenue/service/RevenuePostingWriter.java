package id.co.nativeapp.finance.revenue.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts a consumed {@code SaleRecorded} to
 * the ledger and accumulates the consolidated-revenue read model — idempotently.
 *
 * <p>It is a distinct bean (not a private method on {@link RevenuePostingService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC, and
 * the tenant GUC is exactly what makes the RLS {@code WITH CHECK} pass on the inserts (rule 5). The
 * caller ({@link RevenuePostingService}) binds the tenant from the event's {@code company_id}
 * before invoking this method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Everything below happens in ONE transaction: the
 * dedupe claim and the side effects commit (or roll back) together. {@link
 * ProcessedEventStore#processOnce} claims the event UUID; only the FIRST delivery runs the handler,
 * so a re-delivered {@code SaleRecorded} (same event id) is a clean no-op — no second ledger
 * posting, the read model is not double-counted. The {@code source_event_id UNIQUE} constraint on
 * {@code ledger_posting} is the database backstop behind this.
 *
 * <p><strong>Concurrency (rule 3 / §3.2).</strong> The read-model accumulation is an
 * <em>atomic</em> {@code INSERT … ON CONFLICT … DO UPDATE} (see {@link #UPSERT_REVENUE_SQL}) rather
 * than a read-modify-write. Two distinct sales for the same {@code (company_id, period, currency)}
 * — arriving on different partitions or under listener concurrency &gt; 1 — therefore accumulate
 * correctly without a lost-update window and without an optimistic-lock / unique-conflict that
 * would otherwise be mistaken for a poison record and routed to the DLT (silently dropping valid
 * revenue). The whole transaction (ledger insert + {@code ProcessedEventStore} claim + this upsert)
 * commits together under the RLS GUC the aspect set, so the {@code WITH CHECK} binds {@code
 * company_id}.
 */
@Component
public class RevenuePostingWriter {

  /**
   * Atomic accumulate for the {@code consolidated_revenue} read model: insert a fresh accumulator
   * for a {@code (company_id, period, currency)} or, if one already exists, add this posting's
   * minor units onto the stored total in a single statement. There is NO read-modify-write window,
   * so concurrent distinct sales for the same key never lose an update (§3.2). {@code now()} and
   * the actor stamp the Auditable {@code updated_at}/{@code updated_by} on every accumulate; {@code
   * created_at}/{@code created_by} are set only on the first insert and never overwritten. RLS
   * still applies: the GUC is set by the aspect for this transaction, and the policy's {@code WITH
   * CHECK} binds {@code company_id} on the insert path.
   */
  private static final String UPSERT_REVENUE_SQL =
      """
      INSERT INTO consolidated_revenue
          (id, period, total_minor, currency,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, period, currency) DO UPDATE SET
          total_minor = consolidated_revenue.total_minor + EXCLUDED.total_minor,
          updated_at  = now(),
          updated_by  = EXCLUDED.updated_by,
          version     = consolidated_revenue.version + 1
      """;

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public RevenuePostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      JdbcTemplate jdbcTemplate,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Posts the event's revenue to the ledger and accumulates the read model, exactly once per event
   * id. Must be called inside a {@link TenantContext} scope bound to the event's {@code company_id}
   * (the auto-RLS aspect sets the tenant GUC for this transaction from that scope).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean post(SaleRecordedEvent event) {
    // processOnce claims the event id and runs the handler only on the first delivery.
    // It runs INSIDE this transaction, so the dedupe insert and the posting + read-model
    // update commit atomically: a re-delivery claims nothing and posts nothing.
    return processedEvents.processOnce(event.eventId(), () -> postRevenue(event));
  }

  private void postRevenue(SaleRecordedEvent event) {
    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // 0) GUARD the single-base-currency invariant (#26): a sale whose currency diverges from the
    //    period's already-established currency violates the company's immutable base currency (a
    //    producer sent the wrong currency). Fail closed BEFORE writing anything — the consume
    //    transaction rolls back and the record is DLT'd — rather than silently creating a divergent
    //    second-currency read-model row that detonates later as a read-time 500.
    pnlReadModel.requireConsistentCurrency(period, amount);

    // 1) RESOLVE the REVENUE gl_account via the versioned, effective-dated mapping_rule
    //    (CQRS: resolve on write). Stamped on the posting as its dimension.
    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 2) Append the immutable, dimensional ledger posting. source_event_id is UNIQUE, so even
    //    if the ProcessedEventStore claim were ever bypassed, a duplicate posting is impossible.
    LedgerPosting posting =
        new LedgerPosting(event.businessId(), period, amount, glAccountCode, event.eventId());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 3) Atomically accumulate the consolidated-revenue read model for this
    //    tenant+period+currency in the SAME transaction. A single INSERT ... ON CONFLICT
    //    DO UPDATE adds onto the stored total with no read-modify-write window, so concurrent
    //    distinct sales for the same key never lose an update (§3.2) and never collide on the
    //    unique constraint in a way that would DLT-drop valid revenue.
    String currencyCode = amount.currency().getCurrencyCode();
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        amount.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 4) Atomically accumulate the consolidated P&L read model's REVENUE leg (same
    //    no-read-modify-write upsert), in the SAME transaction. The P&L's net = revenue -
    //    expense; this moves the revenue leg.
    pnlReadModel.addRevenue(period, amount, companyId, actor);

    // 5) Double-entry GL journal — SAME transaction, SAME processOnce claim. Build a balanced
    //    journal entry from the illustrative posting template (SALE: Dr CASH_CLEARING / Cr REVENUE)
    //    and save it alongside the dimensional posting. The source_event_id UNIQUE constraint on
    //    journal_entry is the DB backstop — a re-delivered event is already claimed by processOnce
    //    above, so this line is never reached on a re-delivery.
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.SALE, amount, event.occurredAt(), event.eventId(), "SaleRecorded");
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
