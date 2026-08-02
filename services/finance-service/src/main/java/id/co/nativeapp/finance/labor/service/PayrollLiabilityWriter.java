package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.ReversalEventIds;
import id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that books a consumed {@code
 * PayrollLiabilitiesPosted} run's LIABILITY recognition entry — ONE balanced ad-hoc {@link
 * JournalEntry}: {@code Dr LABOR_CLEARING (6900)} for the run's total employer-borne labor cost /
 * {@code Cr} one leg per non-zero liability bucket (a negative bucket posts as the opposite, Dr,
 * side) — and runs APPEND-ONLY supersession on the LIABILITY dimension, idempotently (ADR 0032,
 * Track P phase P4). The payroll counterpart of {@code id.co.nativeapp.finance.tax.service}'s
 * {@code TaxSettlementWriter}/{@code TaxFilingWriter}: an AD-HOC entry built directly via {@link
 * RoleAccountResolver}, not a fixed {@code posting_template} (the leg count is variable — 1 to 5
 * non-zero buckets — and a bucket may sit on either side depending on its sign).
 *
 * <p><strong>Two independent lifecycles on {@code payroll_run_ledger} (ADR 0032).</strong> This
 * writer reads/writes ONLY the {@code liability_entry_id}/{@code liability_state} columns of the
 * shared control row — never the pre-existing {@code state}/{@code allocated_sum_minor}/{@code
 * control_total_minor} columns {@link LaborCostPostingWriter}/{@link PayrollReconciliationWriter}
 * own. This avoids a cross-writer coordination gap: if the shared {@code state} column were reused
 * for the liability dimension too, a prior run flipped to {@code SUPERSEDED} by {@code
 * LaborCostPostingWriter} (because ITS bucket for the higher-seq run arrived first) would make this
 * writer's own supersession scan blind to that prior run's liability entry, which would then never
 * be reversed. {@link PayrollRunLedgerRepository#findActiveLiabilityPriorRuns}/{@link
 * PayrollRunLedgerRepository#existsActiveHigherLiabilityRun} scope the scan to {@code
 * liability_state} instead.
 *
 * <p><strong>Idempotency (rule 3).</strong> Everything below happens in ONE transaction: the {@link
 * ProcessedEventStore#processOnce} dedupe claim, the run-ledger upsert, any contra reversal, the
 * entry + line inserts — commit (or roll back) together. A re-delivered event (same event id) is a
 * clean no-op, and {@code journal_entry.source_event_id UNIQUE} is the DB backstop.
 *
 * <p><strong>Supersession (append-only), mirroring {@link LaborCostPostingWriter} steps 2/2a/6
 * EXACTLY</strong> — a higher {@code run_seq} of the SAME {@code (company, period, run_type)}
 * reverses each prior ACTIVE liability entry (one contra per PRIMARY, swapping every line's
 * debit/credit side) and flips the prior row's {@code liability_state} to {@code SUPERSEDED}; an
 * OUT-OF-ORDER lower-seq event that arrives AFTER a higher-seq run's liability already posted is
 * ALREADY superseded — it still posts its own PRIMARY (the event genuinely arrived — append-only
 * audit) but IMMEDIATELY appends its own compensating reversal so it nets to zero. Both reversal
 * paths derive a DETERMINISTIC synthetic {@code source_event_id} ({@link
 * ReversalEventIds#forPriorPosting}) claimed through {@code processOnce} + the UNIQUE backstop, so
 * re-delivery never double-reverses.
 *
 * <p><strong>Unmapped role → SUSPENSE, never dropped (HR-3).</strong> A {@code liability_role}
 * string that does not name a known {@link AccountRole}, or a role with no effective {@code
 * role_account_map} row, resolves to {@link RoleAccountResolver#SUSPENSE_ACCOUNT_CODE} with a
 * logged WARN — the money still posts, on the books, just not to the intended control account.
 */
@Component
public class PayrollLiabilityWriter {

  private static final Logger log = LoggerFactory.getLogger(PayrollLiabilityWriter.class);

  private final PayrollRunLedgerRepository runLedgerRepository;
  private final ProcessedEventStore processedEvents;
  private final RoleAccountResolver roleAccountResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  public PayrollLiabilityWriter(
      PayrollRunLedgerRepository runLedgerRepository,
      ProcessedEventStore processedEvents,
      RoleAccountResolver roleAccountResolver,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository) {
    this.runLedgerRepository = runLedgerRepository;
    this.processedEvents = processedEvents;
    this.roleAccountResolver = roleAccountResolver;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
  }

  /**
   * Posts the run's liability recognition entry exactly once per event id. Must be called inside a
   * {@link TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean post(PayrollLiabilitiesPostedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postLiability(event));
  }

  private void postLiability(PayrollLiabilitiesPostedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // 1) Serialize per (company, period, run_type) with the SAME deterministic advisory lock
    //    LaborCostPostingWriter/PayrollReconciliationWriter take, BEFORE any run-row read/insert —
    //    all three writers that may touch this run's shared control row serialize with each other.
    runLedgerRepository.lockPeriod(companyId + ":" + event.period() + ":" + event.runType());
    PayrollRunLedger runRow = upsertRunRow(event, companyId);

    // 2) Supersession: reverse each prior run's ACTIVE liability entry (own dimension — see the
    //    class javadoc on why this is liability_state, not the shared state column).
    for (PayrollRunLedger prior :
        runLedgerRepository.findActiveLiabilityPriorRuns(
            event.period(), event.runType(), event.runSeq())) {
      reversePriorRunLiability(prior, companyId);
      prior.markLiabilitySuperseded();
      runLedgerRepository.save(prior);
    }

    // 2a) Out-of-order supersession guard (mirrors LaborCostPostingWriter step 2a EXACTLY): an
    //     ACTIVE liability entry with a HIGHER run_seq of the SAME run_type already exists. This
    //     late lower-seq event is ALREADY superseded — it still posts its own PRIMARY (append-only
    //     audit) but immediately self-contras (step 6 below) so it contributes zero net.
    boolean alreadySuperseded =
        runLedgerRepository.existsActiveHigherLiabilityRun(
            event.period(), event.runType(), event.runSeq());

    // 3) Build + persist the balanced ad-hoc liability entry.
    JournalEntry entry = buildLiabilityEntry(event);
    persistEntry(entry, companyId);

    // 4) Record the posted liability entry on the run row.
    runRow.markLiabilityPosted(entry.getId());
    runLedgerRepository.save(runRow);

    // 5/6) Out-of-order self-supersession: immediately contra THIS just-posted PRIMARY so the late
    //      lower-seq event nets to zero, and mark the liability dimension SUPERSEDED.
    if (alreadySuperseded) {
      log.warn(
          "PayrollLiabilitiesPosted runId={} period={} runType={} run_seq={} arrived AFTER a"
              + " higher-seq run's liability entry is already active; posting its compensating"
              + " reversal so it nets to zero (no double-count) and marking the liability dimension"
              + " SUPERSEDED",
          event.payrollRunId(),
          event.period(),
          event.runType(),
          event.runSeq());
      UUID reversalEventId = ReversalEventIds.forPriorPosting(entry.getId());
      processedEvents.processOnce(
          reversalEventId, () -> appendReversalEntry(entry.getId(), reversalEventId, companyId));
      runRow.markLiabilitySuperseded();
      runLedgerRepository.save(runRow);
    }
  }

  /**
   * Builds (but does not persist) the balanced liability recognition entry: {@code Dr
   * LABOR_CLEARING} for {@link PayrollLiabilitiesPostedEvent#employerCostTotal()} / one leg per
   * non-zero bucket — a positive bucket credits its resolved role account, a negative bucket debits
   * it for the absolute value instead (the December Art-17 true-up refund month, ADR 0031 — see the
   * class javadoc for the balance derivation). Public + pure (no DB writes beyond the read-only
   * {@link RoleAccountResolver} lookups) so a unit test can assert the exact legs with a mocked
   * resolver, mirroring {@code TaxFilingWriter#buildFilingEntry}.
   */
  public JournalEntry buildLiabilityEntry(PayrollLiabilitiesPostedEvent event) {
    UUID entryId = UUID.randomUUID();
    String currency = event.employerCostTotal().currency().getCurrencyCode();
    Instant occurredAt = event.postedAt();

    List<JournalLine> lines = new ArrayList<>(event.liabilities().size() + 1);
    int lineNo = 1;

    String clearingCode = resolveOrSuspense(AccountRole.LABOR_CLEARING, occurredAt);
    lines.add(JournalLine.debit(entryId, lineNo++, clearingCode, event.employerCostTotal()));

    for (PayrollLiabilitiesPostedEvent.LiabilityBucket bucket : event.liabilities()) {
      long amountMinor = bucket.amount().amountMinor();
      if (amountMinor == 0L) {
        continue; // defensive — the producer omits zero buckets
      }
      String accountCode = resolveBucketAccount(bucket.liabilityRole(), occurredAt);
      if (amountMinor > 0L) {
        lines.add(JournalLine.credit(entryId, lineNo++, accountCode, bucket.amount()));
      } else {
        lines.add(JournalLine.debit(entryId, lineNo++, accountCode, bucket.amount().negate()));
      }
    }

    return JournalEntry.balanced(
        entryId,
        event.period(),
        occurredAt,
        "Payroll liability recognition (" + event.period() + ")",
        currency,
        event.eventId(),
        event.usesIllustrativeRules(),
        lines);
  }

  /**
   * Resolves a liability bucket's {@code liability_role} string to an {@link AccountRole} by name,
   * then to a concrete GL account. An unrecognised role string (a future producer role this finance
   * version does not yet know) fails safe to SUSPENSE with a logged WARN — money is never dropped
   * for want of a matching enum constant.
   */
  private String resolveBucketAccount(String liabilityRole, Instant occurredAt) {
    AccountRole role;
    try {
      role = AccountRole.valueOf(liabilityRole);
    } catch (IllegalArgumentException unrecognisedRole) {
      log.warn(
          "Unrecognised PayrollLiabilitiesPosted liability_role='{}'; routing to suspense {} —"
              + " money is preserved on the books, not dropped",
          liabilityRole,
          RoleAccountResolver.SUSPENSE_ACCOUNT_CODE);
      return RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
    }
    return resolveOrSuspense(role, occurredAt);
  }

  private String resolveOrSuspense(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      log.warn(
          "No role_account_map mapping for role={} at {}; routing to suspense {} — money is"
              + " preserved on the books, not dropped",
          role,
          occurredAt,
          RoleAccountResolver.SUSPENSE_ACCOUNT_CODE);
      return RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
    }
    return accountCode;
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    entry.setCompanyId(companyId);
    // saveAndFlush forces the journal_entry INSERT before the FK'd line INSERTs (same tx).
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

  /** Finds this run's control row or opens a fresh one stamped with the event tenant. */
  private PayrollRunLedger upsertRunRow(PayrollLiabilitiesPostedEvent event, String companyId) {
    return runLedgerRepository
        .findByPayrollRunIdAndRunSeq(event.payrollRunId(), event.runSeq())
        .orElseGet(
            () -> {
              PayrollRunLedger row =
                  new PayrollRunLedger(
                      event.payrollRunId(),
                      event.period(),
                      event.runSeq(),
                      event.runType(),
                      event.employerCostTotal().currency().getCurrencyCode(),
                      event.usesIllustrativeRules());
              row.setCompanyId(companyId);
              return row;
            });
  }

  /**
   * Posts the REVERSAL contra of a superseded prior run's liability entry (per-leg debit ↔ credit
   * swap) — same account codes, amounts negated by construction of the swap. APPEND-ONLY (the prior
   * entry is never mutated). Idempotent: the synthetic reversal id runs through {@code processOnce}
   * + the {@code journal_entry.source_event_id} UNIQUE backstop.
   */
  private void reversePriorRunLiability(PayrollRunLedger prior, String companyId) {
    UUID priorEntryId = prior.getLiabilityEntryId();
    if (priorEntryId == null) {
      // Excluded by findActiveLiabilityPriorRuns's own filter; defensive no-op should it ever
      // change.
      return;
    }
    UUID reversalEventId = ReversalEventIds.forPriorPosting(priorEntryId);
    boolean firstReversal =
        processedEvents.processOnce(
            reversalEventId, () -> appendReversalEntry(priorEntryId, reversalEventId, companyId));
    if (!firstReversal) {
      log.debug(
          "Reversal of prior liability entry {} already applied (eventId={}) — no double-reverse",
          priorEntryId,
          reversalEventId);
    }
  }

  /**
   * Builds and saves a balanced contra journal entry by negating each line of the ORIGINAL entry
   * (debit ↔ credit swap, same account codes) — mirrors {@code
   * ReversalPostingWriter#buildAndSavePerLegReversalEntryFromLines} exactly. Posts into the
   * ORIGINAL entry's own {@code period}/{@code occurredAt} (supersession is always within the SAME
   * {@code (company, period, run_type)}, so this is identical to the superseding event's period in
   * practice).
   *
   * <p>If the original entry (or its lines) cannot be found — should not occur for a valid
   * liability entry — logs an error and returns without saving: the {@code processOnce} claim has
   * already been consumed, so the event is not re-delivered, and the operator must investigate.
   * Money is not silently dropped: the original entry's postings stay exactly as they were.
   */
  private void appendReversalEntry(UUID originalEntryId, UUID reversalEventId, String companyId) {
    JournalEntry original = journalEntryRepository.findById(originalEntryId).orElse(null);
    List<JournalLineReversalView> originalLines =
        journalLineRepository.findLinesByEntryId(originalEntryId);
    if (original == null || originalLines.isEmpty()) {
      log.error(
          "Liability reversal: original journal_entry {} (or its lines) not found; this should not"
              + " occur for a valid liability entry. Reversal skipped — the original postings are"
              + " unaffected. reversalEventId={}",
          originalEntryId,
          reversalEventId);
      return;
    }

    UUID contraEntryId = UUID.randomUUID();
    String currency = original.getCurrency().strip();
    List<JournalLine> contraLines = new ArrayList<>(originalLines.size());
    int lineNo = 1;
    for (JournalLineReversalView orig : originalLines) {
      if (orig.getDebitMinor() > 0) {
        contraLines.add(
            JournalLine.credit(
                contraEntryId,
                lineNo,
                orig.getAccountCode(),
                Money.ofMinor(orig.getDebitMinor(), currency)));
      } else {
        contraLines.add(
            JournalLine.debit(
                contraEntryId,
                lineNo,
                orig.getAccountCode(),
                Money.ofMinor(orig.getCreditMinor(), currency)));
      }
      lineNo++;
    }

    JournalEntry contraEntry =
        JournalEntry.balanced(
            contraEntryId,
            original.getPeriod(),
            original.getOccurredAt(),
            "Payroll liability supersession reversal (" + original.getPeriod() + ")",
            currency,
            reversalEventId,
            original.isUsesIllustrativeRules(),
            contraLines);
    persistEntry(contraEntry, companyId);
  }
}
