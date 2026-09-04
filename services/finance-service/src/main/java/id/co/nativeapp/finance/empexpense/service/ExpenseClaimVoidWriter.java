package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseClaimLedgerRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that posts the exact contra of a consumed
 * {@code ExpenseClaimVoided} — the correction path for an APPROVED, un-settled claim (ADR 0030).
 * Unwinds the dimensional {@code ledger_posting} EXPENSE leg AND the P&amp;L expense accumulator by
 * the negated claim amount, and writes a {@link EventKind#EXPENSE_CLAIM_VOID} double-entry GL
 * contra (Dr {@code 2600 Employee Expense Payable} / Cr the generic EXPENSE role — V39).
 *
 * <p>A distinct bean from {@link ExpenseClaimPostingService} so the method is invoked through the
 * Spring proxy (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The {@link ProcessedEventStore#processOnce}
 * dedupe claim and the side effects commit (or roll back) together, exactly like {@link
 * ExpenseClaimPostingWriter}.
 *
 * <p><strong>Same account, contra amount, the VOID's OWN period.</strong> The dimensional contra
 * resolves the SAME expense account the original approval posted to — {@link
 * GlAccountResolver#resolveExpense} evaluated at the ORIGINAL {@code approvedAt} (so it reverses
 * the exact account even if the mapping has since changed) — and hits the SAME {@code business_id}
 * (the catalog's explicit contract: "the contra hits the same business_id"). The P&amp;L unwind
 * negates the amount into the {@code consolidated_pnl} row for the VOID's OWN period (not
 * necessarily the approval's period — a cross-period void, e.g. approved in June and voided in
 * July, unwinds into July's accumulator, exactly like {@code ReversalPostingWriter}/{@code
 * SaleVoidedEvent} unwinds into the void's own {@code occurredAt}-derived period, not the original
 * sale's — the two periods only coincide when the void lands in the same month as the approval).
 *
 * <p><strong>Claim-ledger settled-check (ADR 0030 §7, review W1/S3).</strong> The settle-once guard
 * now lives on the shared per-claim {@link EmployeeExpenseClaimLedger} row: a row with {@code
 * settledAt} already stamped is a LOUD logged skip (no amounts logged) — money already moved, never
 * silently reversed. A void with NO row at all (the claim's approval is missing or has not arrived
 * yet — an out-of-order/lost-approval case, ADR 0030 §7) is the same loud-WARN pattern but still
 * posts the contra, exactly as before finding no guard row did; there is simply nothing to stamp.
 * On success the row (if one exists) is stamped with {@code voidedAt}/{@code voidEntryId}.
 */
@Component
public class ExpenseClaimVoidWriter {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimVoidWriter.class);

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final EmployeeExpenseClaimLedgerRepository claimLedgerRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ExpenseClaimVoidWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      GeneralLedgerWriter generalLedgerWriter,
      EmployeeExpenseClaimLedgerRepository claimLedgerRepository) {
    this.ledgerRepository = ledgerRepository;
    this.generalLedgerWriter = generalLedgerWriter;
    this.processedEvents = processedEvents;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.claimLedgerRepository = claimLedgerRepository;
  }

  /**
   * Posts the void's contra to the ledger and unwinds the P&amp;L read model, exactly once per
   * event id. Must be called inside a {@link TenantContext} scope bound to the event's {@code
   * company_id}.
   *
   * @return {@code true} if this delivery ran (first delivery — which may still be a no-op contra
   *     when the claim-ledger row is already settled, see below), {@code false} if skipped as a
   *     duplicate (re-delivery of the same event id)
   */
  @Transactional
  public boolean postVoided(ExpenseClaimVoidedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postVoidReversal(event));
  }

  private void postVoidReversal(ExpenseClaimVoidedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    Optional<EmployeeExpenseClaimLedger> existing =
        claimLedgerRepository.findByClaimId(event.claimId());

    // Defense-in-depth: money already moved via a settlement must never be silently touched again.
    // No amounts are logged (rule 6 discipline extended to a defensive log line).
    if (existing.isPresent() && existing.get().isSettled()) {
      log.warn(
          "ExpenseClaimVoided arrived for an ALREADY-SETTLED claim claimId={} (eventId={});"
              + " skipping — money has already moved, this requires human follow-up",
          event.claimId(),
          event.eventId());
      return;
    }

    if (existing.isEmpty()) {
      log.warn(
          "ExpenseClaimVoided for an UNRECOGNIZED claim claimId={} (eventId={}) — approval missing"
              + " or late; posting the contra and self-healing a VOIDED claim-ledger row the late"
              + " approval will reconcile onto",
          event.claimId(),
          event.eventId());
    }

    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.voidedAt());

    // Resolve the SAME account the original approval posted to: the mapping_rule effective AT the
    // ORIGINAL approvedAt, so the contra reverses the exact account even if the mapping has since
    // changed.
    GlAccountResolution resolution =
        glAccountResolver.resolveExpense(event.glHint(), event.approvedAt());

    // 1) Contra dimensional ledger posting — hits the SAME business_id the approval posted under,
    //    negated amount, marked REVERSAL for audit traceability (the ReversalPostingWriter idiom).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.orgUnitId(),
            period,
            amount.negate(),
            resolution.accountCode(),
            event.eventId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind the P&L EXPENSE leg — into the VOID's OWN period bucket (see the class javadoc: a
    //    cross-period void unwinds where the void lands, not where the approval did; the
    //    SaleVoided/ReversalPostingWriter precedent).
    pnlReadModel.addExpense(period, amount.negate(), companyId, actor);

    // 3) Double-entry GL contra — Dr EMPLOYEE_EXPENSE_PAYABLE / Cr EXPENSE (V39 illustrative
    //    template, the exact contra of EXPENSE_CLAIM_APPROVED).
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.EXPENSE_CLAIM_VOID,
            period,
            amount,
            event.voidedAt(),
            event.eventId(),
            "ExpenseClaimVoided",
            false);
    generalLedgerWriter.post(glEntry, companyId);

    // 4) Stamp the claim-ledger row (ADR 0030 §4 drill-down). When NO row exists (void arrived
    //    before the approval — cross-topic reorder, QA sweep 2026-08-05), SELF-HEAL one carrying
    //    only the void facts (the unrecognizedSettlement precedent): the late approval then
    //    reconciles its recognition columns onto it while voided_at stays stamped, so the
    //    drill-down never shows an actually-voided claim as outstanding.
    if (existing.isPresent()) {
      EmployeeExpenseClaimLedger row = existing.get();
      row.applyVoid(event.voidedAt(), glEntry.getId());
      claimLedgerRepository.save(row);
    } else {
      EmployeeExpenseClaimLedger row =
          EmployeeExpenseClaimLedger.unrecognizedVoid(
              event.claimId(),
              event.employeeId(),
              event.orgUnitId(),
              amount,
              event.voidedAt(),
              glEntry.getId());
      row.setCompanyId(companyId);
      claimLedgerRepository.saveAndFlush(row);
    }
  }
}
