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
 *   <li>ZERO: no journal entry — the event is still claimed by {@code processOnce} so redelivery
 *       is a no-op
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
                  + " — variance quarantined for manual posting"),
          "finance.register.sealed-period-quarantine",
          companyId,
          null);
      return;
    }

    long overShort = event.overShortMinor();
    if (overShort == 0) {
      log.info(
          "RegisterSessionClosed {}: zero variance for session {} — no entry",
          event.eventId(),
          event.sessionId());
      return;
    }
    if (overShort == Long.MIN_VALUE) {
      // Math.abs(Long.MIN_VALUE) stays negative — an impossible drawer figure; poison.
      throw new IllegalStateException("over_short_minor out of range: " + overShort);
    }

    Money magnitude = Money.ofMinor(Math.abs(overShort), event.currency());
    requireConsistentGlCurrency(period, magnitude);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(event, entryId, magnitude, period);
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
    log.info(
        "Posted register variance {} {} for session {} (entry {})",
        overShort,
        event.currency(),
        event.sessionId(),
        entryId);
  }

  /**
   * Builds (but does not persist) the balanced 2-line variance entry — public and pure besides the
   * resolver lookups, so a unit test can assert the exact legs per sign with a mocked resolver (the
   * ReconciliationWriter#buildEntry pattern).
   */
  public JournalEntry buildEntry(
      RegisterSessionClosedEvent event, UUID entryId, Money magnitude, String period) {
    Instant occurredAt = event.closedAt();
    boolean isShort = event.overShortMinor() < 0;
    String clearingCode = requireMapped(AccountRole.CASH_CLEARING, occurredAt);
    String varianceCode =
        requireMapped(
            isShort ? AccountRole.CASH_SHORT_EXPENSE : AccountRole.CASH_OVER_INCOME, occurredAt);

    List<JournalLine> lines = new ArrayList<>(2);
    if (isShort) {
      lines.add(JournalLine.debit(entryId, 1, varianceCode, magnitude));
      lines.add(JournalLine.credit(entryId, 2, clearingCode, magnitude));
    } else {
      lines.add(JournalLine.debit(entryId, 1, clearingCode, magnitude));
      lines.add(JournalLine.credit(entryId, 2, varianceCode, magnitude));
    }

    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        "Register close variance (selisih kas " + (isShort ? "kurang" : "lebih") + ")",
        magnitude.currency().getCurrencyCode(),
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
