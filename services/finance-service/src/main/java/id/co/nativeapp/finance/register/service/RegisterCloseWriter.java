package id.co.nativeapp.finance.register.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts the register-close cash variance (selisih kas, ADR 0036) — the ONLY money this consumer
 * writes. Revenue was recognized at sale time; this entry trues {@code CASH_CLEARING} (1900) to the
 * physically counted drawer so the ADR-0016 bank sweep then matches deposits:
 *
 * <ul>
 *   <li>SHORT ({@code over_short < 0}): {@code Dr CASH_SHORT_EXPENSE (5700) / Cr CASH_CLEARING}
 *   <li>OVER ({@code over_short > 0}): {@code Dr CASH_CLEARING / Cr CASH_OVER_INCOME (4300)}
 *   <li>ZERO: no journal entry — the event is still claimed by {@code processOnce} so redelivery is
 *       a no-op
 * </ul>
 *
 * <p>Idempotency: {@link ProcessedEventStore#processOnce} claims the event UUID inside THIS
 * transaction; {@code journal_entry.source_event_id} UNIQUE is the DB backstop. A sealed period
 * ({@code tax_filing} on {@code periodOf(closed_at)} — ADR 0017) quarantines to the error inbox
 * with NO posting, mirroring the SaleRecorded consumer. Single-base-currency guard as everywhere.
 */
@Component
public class RegisterCloseWriter {

  private static final Logger log = LoggerFactory.getLogger(RegisterCloseWriter.class);

  private final ProcessedEventStore processedEvents;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final LedgerPostingRepository ledgerRepository;
  private final ErrorInboxWriter errorInbox;
  private final JdbcTemplate jdbcTemplate;

  public RegisterCloseWriter(
      ProcessedEventStore processedEvents,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      LedgerPostingRepository ledgerRepository,
      ErrorInboxWriter errorInbox,
      JdbcTemplate jdbcTemplate) {
    this.processedEvents = processedEvents;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
    this.ledgerRepository = ledgerRepository;
    this.errorInbox = errorInbox;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Claims the event id and, on first delivery, posts the variance.
   *
   * @return true when this delivery ran the handler; false on a re-delivery
   */
  @Transactional
  public boolean post(RegisterSessionClosedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postVariance(event));
  }

  private void postVariance(RegisterSessionClosedEvent event) {
    String companyId = TenantContext.require().companyId();
    String period = LedgerPosting.periodOf(event.closedAt());

    // Sealed-period quarantine (defense in depth — closed_at is producer-stamped "now", but the
    // guard mirrors the SaleRecorded consumer): post NOTHING, record for the accountant, keep the
    // processOnce claim so redelivery cannot retry into sealed books.
    if (ledgerRepository.sealedPeriodExists(period)) {
      log.warn(
          "RegisterSessionClosed {} targets sealed period {} — quarantined, not posted",
          event.eventId(),
          period);
      errorInbox.record(
          new IllegalStateException(
              "RegisterSessionClosed "
                  + event.eventId()
                  + " closed_at "
                  + event.closedAt()
                  + " targets sealed period "
                  + period
                  + " — variance "
                  + event.overShortMinor()
                  + " "
                  + event.currency()
                  + " quarantined for manual posting"),
          "finance.register.sealed-period-quarantine",
          companyId,
          null);
      return;
    }

    // Collect every tender with a non-zero variance: cash (top-level) + each non-cash line
    // (ADR 0038). Zero across all → no entry (the event stays claimed by processOnce).
    List<TenderVariance> variances = collectVariances(event);
    if (variances.isEmpty()) {
      log.info(
          "RegisterSessionClosed {}: zero variance across all tenders for session {} — no entry",
          event.eventId(),
          event.sessionId());
      return;
    }

    // rule 8: validate the ISO code + the single-base-currency guard, once for the event.
    requireConsistentGlCurrency(period, Money.ofMinor(0L, event.currency()));

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(event, entryId, period);
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
    log.info(
        "Posted register close variance for session {} across {} tender(s) (entry {})",
        event.sessionId(),
        variances.size(),
        entryId);
  }

  /**
   * The tenders with a non-zero variance to post: cash (from the top-level {@code over_short}) plus
   * each non-cash reconciliation line. A non-cash line that does not resolve to a NON-cash clearing
   * role (cash-in-tenders, or an unknown tender) is a poison event → DLT.
   */
  private List<TenderVariance> collectVariances(RegisterSessionClosedEvent event) {
    List<TenderVariance> variances = new ArrayList<>();
    if (event.overShortMinor() != 0) {
      variances.add(new TenderVariance("CASH", AccountRole.CASH_CLEARING, event.overShortMinor()));
    }
    for (RegisterSessionClosedEvent.TenderReconciliation tender : event.tenders()) {
      if (tender.overShortMinor() == 0) {
        continue;
      }
      AccountRole clearing = AccountRole.clearingRoleForTender(tender.tenderType());
      if (clearing == null || clearing == AccountRole.CASH_CLEARING) {
        throw new IllegalStateException(
            "RegisterSessionClosed tender '"
                + tender.tenderType()
                + "' has no non-cash clearing role — poison event, routing to DLT");
      }
      variances.add(new TenderVariance(tender.tenderType(), clearing, tender.overShortMinor()));
    }
    return variances;
  }

  /** One tender's signed variance to post: its clearing account + the SIGNED over/short. */
  private record TenderVariance(String tenderType, AccountRole clearingRole, long overShortMinor) {}

  /**
   * Builds (but does not persist) the balanced variance entry — one debit/credit pair per tender
   * with a non-zero variance, all in the event currency. Public + pure besides the resolver lookups
   * so a unit test can assert the exact legs (the ReconciliationWriter#buildEntry pattern). SHORT
   * ({@code over_short < 0}) → {@code Dr SHORT_EXPENSE / Cr <clearing>}; OVER → {@code Dr
   * <clearing> / Cr OVER_INCOME}. Cash and every non-cash tender share the SHORT/OVER accounts (ADR
   * 0038); each uses its own clearing account (cash 1900, card 1902, QRIS 1901, online 1250).
   */
  public JournalEntry buildEntry(RegisterSessionClosedEvent event, UUID entryId, String period) {
    Instant occurredAt = event.closedAt();
    String currencyCode = event.currency();
    List<TenderVariance> variances = collectVariances(event);
    List<JournalLine> lines = new ArrayList<>(variances.size() * 2);
    int lineNo = 1;
    for (TenderVariance variance : variances) {
      long overShort = variance.overShortMinor();
      if (overShort == Long.MIN_VALUE) {
        // Math.abs(Long.MIN_VALUE) stays negative — an impossible figure; poison.
        throw new IllegalStateException(
            "over_short_minor out of range for " + variance.tenderType() + ": " + overShort);
      }
      boolean isShort = overShort < 0;
      Money magnitude = Money.ofMinor(Math.abs(overShort), currencyCode);
      String clearingCode = requireMapped(variance.clearingRole(), occurredAt);
      String varianceCode =
          requireMapped(
              isShort ? AccountRole.CASH_SHORT_EXPENSE : AccountRole.CASH_OVER_INCOME, occurredAt);
      if (isShort) {
        lines.add(JournalLine.debit(entryId, lineNo++, varianceCode, magnitude));
        lines.add(JournalLine.credit(entryId, lineNo++, clearingCode, magnitude));
      } else {
        lines.add(JournalLine.debit(entryId, lineNo++, clearingCode, magnitude));
        lines.add(JournalLine.credit(entryId, lineNo++, varianceCode, magnitude));
      }
    }

    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        "Register close variance (selisih kas — per tender)",
        currencyCode,
        event.eventId(),
        true,
        lines);
  }

  /** Fail loud on an unmapped role (V43 seeds both, effective 2000-01-01 — internal fault). */
  private String requireMapped(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for " + role + " at " + occurredAt);
    }
    return accountCode;
  }

  /** The single-base-currency guard (mirrors every settlement writer verbatim). */
  private void requireConsistentGlCurrency(String period, Money amount) {
    String incoming = amount.currency().getCurrencyCode();
    List<String> divergent =
        jdbcTemplate.query(
            "SELECT DISTINCT currency FROM journal_entry WHERE period = ? AND currency <> ?",
            (rs, rowNum) -> rs.getString(1).strip(),
            period,
            incoming);
    if (!divergent.isEmpty()) {
      throw new MismatchedPostingCurrencyException(period, divergent.getFirst(), incoming);
    }
  }
}
