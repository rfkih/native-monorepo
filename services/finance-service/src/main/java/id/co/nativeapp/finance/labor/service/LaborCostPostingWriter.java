package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import id.co.nativeapp.finance.labor.domain.PayrollRunState;
import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.messaging.ReversalEventIds;
import id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.repository.ConsolidatedPnlRepository;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingRole;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that posts a consumed {@code LaborCostAllocated}
 * bucket to the dimensional ledger as an EXPENSE posting, accumulates the consolidated P&amp;L,
 * maintains the {@code payroll_run_ledger} control row, and runs APPEND-ONLY supersession — all
 * idempotently (#23). The labor counterpart of {@code ExpensePostingWriter}.
 *
 * <p>A distinct bean (not a private method on {@link LaborCostPostingService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC the RLS
 * {@code WITH CHECK} needs (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Everything below happens in ONE transaction: the
 * {@link ProcessedEventStore#processOnce} dedupe claim, the run-ledger upsert, the contra
 * reversals, the ledger insert and the P&amp;L move commit (or roll back) together. A re-delivered
 * bucket (same event id) is a clean no-op, and {@code ledger_posting.source_event_id UNIQUE} is the
 * DB backstop.
 *
 * <p><strong>Supersession (append-only).</strong> A higher {@code run_seq} for the same {@code
 * (company, period)} supersedes lower ACTIVE runs: finance posts one REVERSAL contra per prior
 * PRIMARY posting (amount negated, a deterministic synthetic {@code source_event_id}) and flips the
 * prior run to {@link PayrollRunState#SUPERSEDED} — the ledger never mutates. The synthetic
 * reversal ids run through {@code processOnce} + the UNIQUE backstop, so re-delivery never
 * double-reverses.
 *
 * <p><strong>Out-of-order safe.</strong> Buckets post as they arrive (finance never blocks on
 * {@code PayrollPosted}); the running {@code allocated_sum} on the control row is reconciled later
 * when {@code PayrollPosted} arrives ({@link PayrollReconciliationWriter}). A deterministic
 * per-{@code (company, period)} {@code pg_advisory_xact_lock} (see {@link
 * PayrollRunLedgerRepository#lockPeriod}), taken before any run-row access, serializes interleaved
 * deliveries of two runs — including two genuinely NEW runs for which no run row exists yet.
 */
@Component
public class LaborCostPostingWriter {

  private static final Logger log = LoggerFactory.getLogger(LaborCostPostingWriter.class);

  private final LedgerPostingRepository ledgerRepository;
  private final PayrollRunLedgerRepository runLedgerRepository;
  private final ProcessedEventStore processedEvents;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final ConsolidatedPnlRepository pnlRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public LaborCostPostingWriter(
      LedgerPostingRepository ledgerRepository,
      PayrollRunLedgerRepository runLedgerRepository,
      ProcessedEventStore processedEvents,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      ConsolidatedPnlRepository pnlRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.ledgerRepository = ledgerRepository;
    this.runLedgerRepository = runLedgerRepository;
    this.processedEvents = processedEvents;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.pnlRepository = pnlRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Posts the bucket exactly once per event id. Must be called inside a {@link TenantContext} scope
   * bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean post(LaborCostAllocatedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postBucket(event));
  }

  private void postBucket(LaborCostAllocatedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // 1) Serialize per (company, period) with a DETERMINISTIC transaction-scoped advisory lock
    // taken
    //    BEFORE any run-row read/insert. Unlike a SELECT ... FOR UPDATE on the highest existing run
    //    row (which locks NOTHING when no row exists yet), pg_advisory_xact_lock keyed on the
    //    (company, period) pair is held even for the period's very first activity — so two
    // genuinely
    //    NEW runs (run_seq=1 and run_seq=2) processed by parallel READ_COMMITTED consumers cannot
    //    both post a PRIMARY without seeing each other (the double-count CRITICAL, #23). The later
    //    run serializes behind the earlier run's row creation and correctly sees-and-reverses (or
    //    self-supersedes) it. The lock auto-releases at commit/rollback. Then find-or-create THIS
    //    run's row and accumulate the bucket onto its running sum (reconciliation accumulator).
    runLedgerRepository.lockPeriod(companyId + ":" + event.period());
    PayrollRunLedger runRow = upsertRunRow(event, companyId);

    // 1a) Currency-consistency guard (#23, no-FX scope): a bucket whose currency differs from the
    //     run's established currency — OR from an existing consolidated_pnl row for the period —
    //     must NOT silently spawn a second-currency P&L row (keyed on company,period,currency),
    //     which would only surface later as a read-time "Multiple currencies" failure in PnlReader.
    //     Flag the run CURRENCY_MISMATCH and STOP — the divergent bucket is not posted, money is
    // not
    //     dropped (the run is held back from final, loud), and no second P&L row is created.
    //     CURRENCY_MISMATCH is TERMINAL/STICKY (mirrors SUPERSEDED): a once-seen divergence can
    //     never self-heal to RECONCILED on a later in-currency bucket — that would mask that a cost
    //     in another currency was observed and dropped.
    String bucketCurrency = event.amount().currency().getCurrencyCode();
    String divergentFrom = divergentPeriodCurrency(runRow, event.period(), bucketCurrency);
    if (divergentFrom != null) {
      log.error(
          "LaborCostAllocated runId={} period={} run_seq={} bucket currency {} differs from the"
              + " period's established currency {}; marking CURRENCY_MISMATCH (no FX in scope) — the"
              + " bucket is NOT posted to a second-currency P&L row, the period is held back"
              + " terminally (the divergence is sticky and cannot self-heal)",
          event.payrollRunId(),
          event.period(),
          event.runSeq(),
          bucketCurrency,
          divergentFrom);
      runRow.transitionTo(PayrollRunState.CURRENCY_MISMATCH);
      runLedgerRepository.save(runRow);
      return;
    }

    runRow.accumulate(event.amount(), event.usesIllustrativeRules());
    runLedgerRepository.save(runRow);

    // 2) Supersession: if this run supersedes lower-seq ACTIVE runs, reverse each append-only.
    for (PayrollRunLedger prior :
        runLedgerRepository.findActivePriorRuns(event.period(), event.runSeq())) {
      reversePriorRun(prior, companyId, actor);
      prior.transitionTo(PayrollRunState.SUPERSEDED);
      runLedgerRepository.save(prior);
    }

    // 2a) Out-of-order supersession guard (#23, money correctness): an ACTIVE run with a HIGHER
    //     run_seq already exists for this (company, period) — this can happen when run_seq=2
    // buckets
    //     are processed BEFORE run_seq=1 buckets (different partitions / parallel consumers). The
    //     higher run already ran its reversal scan against the rows present then and will NOT see
    //     this late lower-seq PRIMARY, so if we let it stand the period would double-count
    //     (run1 + run2). The incoming lower-seq run is ALREADY superseded: we still APPEND its
    //     PRIMARY (the bucket genuinely arrived — append-only audit) but IMMEDIATELY append its own
    //     compensating REVERSAL so it contributes ZERO net, and flip its run row to SUPERSEDED. The
    //     synthetic reversal id is deterministic, so re-delivery is a clean no-op (idempotent).
    boolean alreadySuperseded =
        runLedgerRepository.existsActiveHigherRun(event.period(), event.runSeq());

    // 3) Resolve the labor gl_account. The UNALLOCATED bucket goes to the explicit 6900 labor-
    //    clearing account (visible suspense); every other bucket re-resolves the hint via
    //    mapping_rule, failing safe to the expense suspense 9999 if unrecognised (money never
    //    dropped — HR-3).
    String account;
    if (event.unallocated()) {
      account = GlAccountResolver.LABOR_CLEARING_ACCOUNT_CODE;
      log.info(
          "UNALLOCATED labor bucket (runId={}, eventId={}) posted to labor-clearing {} — visible"
              + " suspense for an operator to clear once the assignment is known",
          event.payrollRunId(),
          event.eventId(),
          account);
    } else {
      GlAccountResolution resolution =
          glAccountResolver.resolveLabor(event.glAccount(), event.occurredAt());
      account = resolution.accountCode();
      if (!resolution.mapped()) {
        log.warn(
            "Unmappable labor gl_account='{}' (runId={}, eventId={}); posting to suspense {} —"
                + " money is preserved on the books, not dropped",
            event.glAccount(),
            event.payrollRunId(),
            event.eventId(),
            account);
      }
    }

    // 4) Append the PRIMARY EXPENSE labor posting under the run's AUTHORITATIVE period (not
    //    periodOf(occurred_at)). source_event_id is UNIQUE.
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.outletId(),
            event.period(),
            event.amount(),
            account,
            event.eventId(),
            event.payrollRunId(),
            event.runSeq(),
            PostingRole.PRIMARY,
            event.usesIllustrativeRules(),
            event.unallocated());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 4b) Double-entry GL journal — SAME transaction, SAME processOnce claim. PRIMARY only.
    //     period is the run's AUTHORITATIVE event.period() (NOT periodOf(occurredAt)) so the GL
    //     entry lands in the same period as the dimensional posting above; the event's illustrative
    //     flag is OR'd into the entry inside buildEntry.
    //     TODO(GL-labor-reversal): supersession posts a dimensional REVERSAL (step 2 / 6) but NO GL
    //     counterpart yet, so a superseded run's labor overstates the GL period total until the GL
    //     reversal lands. Each entry stays internally balanced (trial balance Σdr==Σcr holds), but
    //     the GL diverges from the dimensional ledger on supersession. Track before the GL becomes
    //     the system of record for statements.
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.LABOR,
            event.period(),
            event.amount(),
            event.occurredAt(),
            event.eventId(),
            "LaborCostAllocated",
            event.usesIllustrativeRules());
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

    // 5) Move the P&L expense leg up, carrying the illustrative flag (sticky-OR on the read model).
    pnlReadModel.addExpense(
        event.period(), event.amount(), companyId, actor, event.usesIllustrativeRules());

    // 6) Out-of-order self-supersession (see 2a): if a higher-seq run is already active,
    // immediately
    //    contra THIS just-posted PRIMARY so the late lower-seq run nets to zero, and mark the run
    //    SUPERSEDED — no double-count, append-only, idempotent.
    if (alreadySuperseded) {
      log.warn(
          "LaborCostAllocated runId={} period={} run_seq={} arrived AFTER a higher-seq run is"
              + " already active; posting its compensating REVERSAL so it nets to zero (no"
              + " double-count) and marking it SUPERSEDED",
          event.payrollRunId(),
          event.period(),
          event.runSeq());
      UUID reversalEventId = ReversalEventIds.forPriorPosting(posting.getId());
      processedEvents.processOnce(
          reversalEventId, () -> appendReversal(posting, reversalEventId, companyId, actor));
      runRow.transitionTo(PayrollRunState.SUPERSEDED);
      runLedgerRepository.save(runRow);
      return;
    }

    // 7) Reconciliation SELF-HEAL on the final bucket (#23): if PayrollPosted arrived BEFORE the
    //    buckets, the run is sitting RECONCILE_FAILED with its control_total already recorded (0 !=
    //    control at the time). Each bucket that lands re-drives the comparison here, so the run
    //    self-heals to RECONCILED on the bucket that completes the sum — WITHOUT depending on a
    //    PayrollPosted re-delivery (which the producer may never emit). If the control total is not
    //    yet known (buckets-first), there is nothing to reconcile against yet — the eventual
    //    PayrollPosted will reconcile.
    reReconcileIfControlKnown(runRow);
    runLedgerRepository.save(runRow);
  }

  /**
   * Re-drives reconciliation from the bucket path once a control total is already on the run row
   * (PayrollPosted-first). Mirrors {@link PayrollReconciliationWriter}'s match/mismatch decision so
   * a run self-heals to {@link PayrollRunState#RECONCILED} on the final bucket. A no-op if no
   * control total is recorded yet, or if the run is already in a TERMINAL state ({@link
   * PayrollRunState#SUPERSEDED} or {@link PayrollRunState#CURRENCY_MISMATCH}). The
   * currency-mismatch guard is what makes a once-seen currency divergence STICKY: a divergent
   * bucket routed the run to the terminal {@code CURRENCY_MISMATCH} and returned earlier, and this
   * self-heal explicitly refuses to clear that back to {@code RECONCILED} when a later in-currency
   * bucket completes the control sum.
   */
  private void reReconcileIfControlKnown(PayrollRunLedger runRow) {
    Money controlTotal = runRow.controlTotal();
    if (controlTotal == null
        || runRow.getState() == PayrollRunState.SUPERSEDED
        || runRow.getState() == PayrollRunState.CURRENCY_MISMATCH) {
      return;
    }
    // The shared reconcile-decision (#35): RECONCILED on a match, else hold RECONCILE_FAILED until
    // a
    // later bucket completes the sum (or the run is genuinely partial).
    if (runRow.reconcileAgainstControl()) {
      log.info(
          "Run runId={} period={} run_seq={} self-healed to RECONCILED on the final bucket:"
              + " allocated_sum={} == control={} (no PayrollPosted re-delivery needed)",
          runRow.getPayrollRunId(),
          runRow.getPeriod(),
          runRow.getRunSeq(),
          runRow.allocatedSum(),
          controlTotal);
    }
  }

  /**
   * The currency this bucket diverges from, or {@code null} if the bucket is consistent. The
   * established period currency is the run row's currency for an EXISTING run row, or any
   * consolidated_pnl row already present for the (company, period). A freshly-opened run row that
   * is the period's first activity establishes the currency, so it never diverges from itself. No
   * FX in scope (#23): a divergence is routed to the terminal {@link
   * PayrollRunState#CURRENCY_MISMATCH} (sticky — a later in-currency bucket cannot self-heal it
   * back to {@code RECONCILED}), and the divergent bucket is never posted, so no second P&amp;L
   * row.
   */
  private String divergentPeriodCurrency(
      PayrollRunLedger runRow, String period, String bucketCurrency) {
    // The run row's own currency is the authoritative check at both ends (computed once, #35):
    // null when the bucket matches it, else the run currency it diverges from.
    String runRowDivergence =
        runRow.currencyCode().equals(bucketCurrency) ? null : runRow.currencyCode();

    // For an already-accumulating run, its own currency is authoritative — decide on it directly.
    if (runRow.allocatedSum().amountMinor() != 0L || runRow.controlTotal() != null) {
      return runRowDivergence;
    }
    // Otherwise compare against any P&L row already established for the period (e.g. a prior run in
    // a
    // different currency). RLS scopes the read to the bound tenant.
    for (ConsolidatedPnl row : pnlRepository.findByPeriod(period)) {
      String periodCurrency = row.expense().currency().getCurrencyCode();
      if (!periodCurrency.equals(bucketCurrency)) {
        return periodCurrency;
      }
    }
    // Fall back to the run row's own seeded currency (set from the first bucket it saw).
    return runRowDivergence;
  }

  /** Finds this run's control row or opens a fresh PENDING one stamped with the event tenant. */
  private PayrollRunLedger upsertRunRow(LaborCostAllocatedEvent event, String companyId) {
    return runLedgerRepository
        .findByPayrollRunIdAndRunSeq(event.payrollRunId(), event.runSeq())
        .orElseGet(
            () -> {
              PayrollRunLedger row =
                  new PayrollRunLedger(
                      event.payrollRunId(),
                      event.period(),
                      event.runSeq(),
                      event.amount().currency().getCurrencyCode(),
                      event.usesIllustrativeRules());
              row.setCompanyId(companyId);
              return row;
            });
  }

  /**
   * Posts one REVERSAL contra per PRIMARY posting of a superseded prior run — same
   * outlet/gl/period, amount negated, a deterministic synthetic {@code source_event_id} — and
   * unwinds each from the P&amp;L. APPEND-ONLY (the prior postings are never mutated). Idempotent:
   * the synthetic id runs through {@code processOnce} + the UNIQUE backstop, so a re-delivered
   * superseding run never reverses twice.
   */
  private void reversePriorRun(PayrollRunLedger prior, String companyId, String actor) {
    List<LedgerPosting> priorPrimaries =
        ledgerRepository.findByPayrollRunIdAndPostingRole(
            prior.getPayrollRunId(), PostingRole.PRIMARY);
    for (LedgerPosting original : priorPrimaries) {
      UUID reversalEventId = ReversalEventIds.forPriorPosting(original.getId());
      // Idempotency claim for THIS reversal inside the surrounding transaction: a re-delivered
      // superseding run derives the same reversal id and claims nothing, so it is a clean no-op.
      boolean firstReversal =
          processedEvents.processOnce(
              reversalEventId, () -> appendReversal(original, reversalEventId, companyId, actor));
      if (!firstReversal) {
        log.debug(
            "Reversal of prior posting {} already applied (eventId={}) — no double-reverse",
            original.getId(),
            reversalEventId);
      }
    }
  }

  private void appendReversal(
      LedgerPosting original, UUID reversalEventId, String companyId, String actor) {
    Money contra = original.getAmount().negate();
    LedgerPosting reversal =
        new LedgerPosting(
            original.getPostingType(),
            original.getBusinessId(),
            original.getPeriod(),
            contra,
            original.getGlAccountCode(),
            reversalEventId,
            original.getPayrollRunId(),
            original.getRunSeq(),
            PostingRole.REVERSAL,
            original.isUsesIllustrativeRules(),
            original.isUnallocated());
    reversal.setCompanyId(companyId);
    ledgerRepository.save(reversal);
    // Unwind the prior run's P&L contribution: a negative expense leg. The illustrative flag is
    // STICKY (sticky-OR never clears), so reversing an illustrative run does NOT un-flag the period
    // — correct, the period was provisional and stays so until a clean run closes it.
    pnlReadModel.addExpense(
        original.getPeriod(), contra, companyId, actor, original.isUsesIllustrativeRules());
  }
}
