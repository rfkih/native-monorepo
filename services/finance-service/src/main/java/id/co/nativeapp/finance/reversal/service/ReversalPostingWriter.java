package id.co.nativeapp.finance.reversal.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work that post reversal ledger entries for consumed
 * {@code SaleVoided} and {@code SaleRefunded} events (ADR 0006, slice 4).
 *
 * <p>A distinct bean from {@link ReversalPostingService} so each method is invoked through the
 * Spring proxy: the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} both engage.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Both methods claim the event UUID via {@link
 * ProcessedEventStore#processOnce} inside the transaction; only the first delivery runs the
 * handler. The {@code source_event_id UNIQUE} constraint on {@code ledger_posting} + {@code
 * journal_entry} are the database backstops.
 *
 * <p><strong>Reversal accounting.</strong> A void/refund is a CONTRA entry: it uses the INVERSE of
 * the original SALE template. The {@code SALE_VOID} / {@code SALE_REFUND} posting templates seed by
 * V16 are {@code Dr REVENUE / Cr CLEARING} (the exact opposite of {@code SALE}'s {@code Dr CLEARING
 * / Cr REVENUE}). The same {@code clearingRoleOverride} mechanism as the original sale routes QRIS
 * / CARD voids and refunds to the correct digital clearing account.
 *
 * <p><strong>Read-model reversal.</strong> The {@code consolidated_revenue} and {@code
 * consolidated_pnl} read models are accumulated with the NEGATED amount ({@link Money#negate()}),
 * so the period totals unwind correctly and the net remains accurate for the dashboard.
 */
@Component
public class ReversalPostingWriter {

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
  public ReversalPostingWriter(
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
   * Posts the reversal for a {@code SaleVoided} event — exactly once per {@code void_id}. Posts a
   * contra ledger entry, unwinds the consolidated-revenue + P&amp;L read models with the negated
   * amount, and writes a {@code SALE_VOID} GL journal entry (contra of the original SALE).
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped
   */
  @Transactional
  public boolean postVoid(SaleVoidedEvent event) {
    return processedEvents.processOnce(event.voidId(), () -> postVoidReversal(event));
  }

  /**
   * Posts the reversal for a {@code SaleRefunded} event — exactly once per {@code refund_id}. Posts
   * a proportional contra ledger entry for the refund amount, unwinds the read models by the
   * refunded amount, and writes a {@code SALE_REFUND} GL journal entry.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if skipped
   */
  @Transactional
  public boolean postRefund(SaleRefundedEvent event) {
    return processedEvents.processOnce(event.refundId(), () -> postRefundReversal(event));
  }

  private void postVoidReversal(SaleVoidedEvent event) {
    Money amount = event.amount();
    // Negated amount: reverses the original revenue accumulation.
    Money negated = amount.negate();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 1) Contra dimensional ledger posting (negated amount, REVENUE type, REVERSAL role).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negated,
            glAccountCode,
            event.voidId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind the consolidated-revenue read model.
    String currencyCode = amount.currency().getCurrencyCode();
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        negated.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3) Unwind the P&L read model's REVENUE leg.
    pnlReadModel.addRevenue(period, negated, companyId, actor);

    // 4) SALE_VOID GL journal contra entry.
    AccountRole clearingRole = resolveClearingRole(event.tenderType());
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.SALE_VOID,
            period,
            amount,
            event.occurredAt(),
            event.voidId(),
            "SaleVoided",
            false,
            clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
    glEntry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(glEntry);
    glEntry
        .getLines()
        .forEach(
            line -> {
              line.setCompanyId(companyId);
              journalLineRepository.save(line);
            });
  }

  private void postRefundReversal(SaleRefundedEvent event) {
    Money refundAmount = event.refundAmount();
    Money negated = refundAmount.negate();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    String glAccountCode = glAccountResolver.resolveRevenue(event.occurredAt());

    // 1) Contra dimensional ledger posting (negated refund amount, REVENUE type, REVERSAL role).
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.REVENUE,
            event.businessId(),
            period,
            negated,
            glAccountCode,
            event.refundId());
    posting.markAsReversal();
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    // 2) Unwind the consolidated-revenue read model by the refunded amount.
    String currencyCode = refundAmount.currency().getCurrencyCode();
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        negated.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3) Unwind the P&L read model's REVENUE leg by the refunded amount.
    pnlReadModel.addRevenue(period, negated, companyId, actor);

    // 4) SALE_REFUND GL journal contra entry (proportional — refundAmount is the gross).
    AccountRole clearingRole = resolveClearingRole(event.tenderType());
    JournalEntry glEntry =
        journalPostingService.buildEntry(
            EventKind.SALE_REFUND,
            period,
            refundAmount,
            event.occurredAt(),
            event.refundId(),
            "SaleRefunded",
            false,
            clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
    glEntry.setCompanyId(companyId);
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
   * Resolves the GL clearing {@link AccountRole} from the original tender type (same logic as
   * {@code RevenuePostingWriter.resolveClearingRole} — inline to avoid cross-feature coupling).
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
