package id.co.nativeapp.finance.stocktake.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
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
 * Posts a completed stocktake's net valued shrinkage (selisih stok, ADR 0038 phase 3) — the ONLY
 * money this consumer writes. Revenue/COGS-on-sale is never re-touched here (this is a periodic
 * stocktake, not perpetual inventory):
 *
 * <ul>
 *   <li>LOSS ({@code shrinkage_minor > 0}): {@code Dr INVENTORY_SHRINKAGE (5800) / Cr INVENTORY
 *       (1100)}
 *   <li>GAIN ({@code shrinkage_minor < 0}): {@code Dr INVENTORY (1100) / Cr INVENTORY_SHRINKAGE
 *       (5800)}
 *   <li>ZERO: no journal entry — the event is still claimed by {@code processOnce} so redelivery is
 *       a no-op
 * </ul>
 *
 * <p>Shrinkage is a genuine operating expense (account 5800), so — exactly like {@code
 * ExpensePostingWriter} — a non-zero entry also lands on BOTH P&amp;L read models the dashboards
 * read, in the SAME transaction: a dimensional {@code LedgerPosting(EXPENSE, businessId, …, 5800)}
 * (the per-outlet rollup) and the {@code consolidated_pnl} expense leg via {@link
 * PnlReadModelWriter#addExpense}. Without them a net loss would reduce only the raw GL trial
 * balance and never the profit an owner actually sees — the very leak ADR 0038 set out to close.
 * The amount carries {@code shrinkage_minor}'s sign directly (positive = loss = a positive expense;
 * negative = a physical overage = a contra-expense that lifts profit back up — the labor-REVERSAL
 * negative precedent), so no branching: the GL {@code buildEntry} keeps its {@code Math.abs} +
 * debit/credit flip, the read models take the signed figure. The dimensional posting is always
 * keyed to 5800 — the sign lives in the amount, never the account — and the asset (1100) leg stays
 * GL-journal-only, mirroring how {@code ExpensePostingWriter} leaves its cash-clearing credit off
 * the P&amp;L model.
 *
 * <p>Idempotency: {@link ProcessedEventStore#processOnce} claims the event UUID inside THIS
 * transaction; {@code journal_entry.source_event_id} AND {@code ledger_posting.source_event_id} are
 * both UNIQUE DB backstops (the one {@code event.eventId()} reused across tables, as {@code
 * ExpensePostingWriter} does). A sealed period ({@code tax_filing} on {@code periodOf(counted_at)}
 * — ADR 0017) quarantines to the error inbox with NO posting — read models included — mirroring the
 * {@code RegisterSessionClosed} consumer. Single-base-currency guard on both the GL and the
 * consolidated read model, as everywhere.
 */
@Component
public class StocktakeWriter {

  private static final Logger log = LoggerFactory.getLogger(StocktakeWriter.class);

  private final ProcessedEventStore processedEvents;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final LedgerPostingRepository ledgerRepository;
  private final PnlReadModelWriter pnlReadModel;
  private final ErrorInboxWriter errorInbox;
  private final JdbcTemplate jdbcTemplate;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public StocktakeWriter(
      ProcessedEventStore processedEvents,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      LedgerPostingRepository ledgerRepository,
      PnlReadModelWriter pnlReadModel,
      ErrorInboxWriter errorInbox,
      JdbcTemplate jdbcTemplate) {
    this.processedEvents = processedEvents;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
    this.ledgerRepository = ledgerRepository;
    this.pnlReadModel = pnlReadModel;
    this.errorInbox = errorInbox;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Claims the event id and, on first delivery, posts the shrinkage.
   *
   * @return true when this delivery ran the handler; false on a re-delivery
   */
  @Transactional
  public boolean post(StocktakeCompletedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postShrinkage(event));
  }

  private void postShrinkage(StocktakeCompletedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();
    String period = LedgerPosting.periodOf(event.countedAt());

    // Sealed-period quarantine (defense in depth — counted_at is producer-stamped "now", but the
    // guard mirrors the RegisterSessionClosed consumer): post NOTHING, record for the accountant,
    // keep the processOnce claim so redelivery cannot retry into sealed books.
    if (ledgerRepository.sealedPeriodExists(period)) {
      log.warn(
          "StocktakeCompleted {} targets sealed period {} — quarantined, not posted",
          event.eventId(),
          period);
      errorInbox.record(
          new IllegalStateException(
              "StocktakeCompleted "
                  + event.eventId()
                  + " counted_at "
                  + event.countedAt()
                  + " targets sealed period "
                  + period
                  + " — shrinkage "
                  + event.shrinkageMinor()
                  + " "
                  + event.currency()
                  + " quarantined for manual posting"),
          "finance.stocktake.sealed-period-quarantine",
          companyId,
          null);
      return;
    }

    if (event.shrinkageMinor() == 0) {
      log.info(
          "StocktakeCompleted {}: zero shrinkage for stocktake {} — no entry",
          event.eventId(),
          event.stocktakeId());
      return;
    }

    // The signed expense figure the read models take verbatim: shrinkage_minor's sign already
    // equals the expense-leg convention (positive = a loss = a positive expense; negative = a
    // physical overage = a contra-expense). rule 8: validate the ISO code + the
    // single-base-currency
    // guard for the event, on BOTH the GL journal and the consolidated read model, BEFORE any
    // write.
    Money signedExpense = Money.ofMinor(event.shrinkageMinor(), event.currency());
    requireConsistentGlCurrency(period, signedExpense);
    pnlReadModel.requireConsistentCurrency(period, signedExpense);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(event, entryId, period);
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }

    // Bring shrinkage onto the two P&L surfaces owners actually read (ADR 0038 W1), in this same
    // transaction — exactly as ExpensePostingWriter does for a genuine expense. The dimensional
    // posting drives the per-outlet rollup (UnitPnlRepository, EXPENSE only); addExpense drives the
    // consolidated_pnl dashboard. Always keyed to INVENTORY_SHRINKAGE (5800) — the sign lives in
    // the
    // amount, never the account — and the 1100 asset leg stays GL-journal-only. usesIllustrative
    // mirrors the GL entry (derived from the resolved INVENTORY/INVENTORY_SHRINKAGE mapping
    // provenance in buildEntry), keeping the dashboard's provisional badge consistent with the
    // books. ledger_posting.source_event_id UNIQUE (the reused event id) is the DB idempotency
    // backstop.
    String shrinkageCode = requireMapped(AccountRole.INVENTORY_SHRINKAGE, event.countedAt());
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE,
            event.businessId(),
            period,
            signedExpense,
            shrinkageCode,
            event.eventId());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);
    pnlReadModel.addExpense(
        period, signedExpense, companyId, actor, entry.isUsesIllustrativeRules());

    log.info(
        "Posted stocktake shrinkage for stocktake {} (entry {})", event.stocktakeId(), entryId);
  }

  /**
   * Builds (but does not persist) the balanced shrinkage entry — one debit/credit pair, in the
   * event currency. Public + pure besides the resolver lookups so a unit test can assert the exact
   * legs (the {@code RegisterCloseWriter#buildEntry} pattern). LOSS ({@code shrinkage_minor > 0}) →
   * {@code Dr INVENTORY_SHRINKAGE / Cr INVENTORY}; GAIN ({@code shrinkage_minor < 0}) → {@code Dr
   * INVENTORY / Cr INVENTORY_SHRINKAGE}.
   *
   * @throws IllegalStateException if {@code shrinkageMinor} is {@code 0} (the caller must filter
   *     the zero case before building an entry — a balanced entry cannot have a zero-magnitude
   *     line) or {@link Long#MIN_VALUE} (unsafe {@code Math.abs}; defense in depth behind {@link
   *     StocktakeCompletedEvent#assertValid()})
   */
  public JournalEntry buildEntry(StocktakeCompletedEvent event, UUID entryId, String period) {
    Instant occurredAt = event.countedAt();
    String currencyCode = event.currency();
    long shrinkage = event.shrinkageMinor();
    if (shrinkage == Long.MIN_VALUE) {
      // Math.abs(Long.MIN_VALUE) stays negative — an impossible figure; poison.
      throw new IllegalStateException("shrinkage_minor out of range: " + shrinkage);
    }
    if (shrinkage == 0) {
      throw new IllegalStateException(
          "shrinkage_minor is zero — no entry should be built for a zero variance");
    }

    boolean isLoss = shrinkage > 0;
    Money magnitude = Money.ofMinor(Math.abs(shrinkage), currencyCode);
    String inventoryCode = requireMapped(AccountRole.INVENTORY, occurredAt);
    String shrinkageCode = requireMapped(AccountRole.INVENTORY_SHRINKAGE, occurredAt);

    List<JournalLine> lines = new ArrayList<>(2);
    if (isLoss) {
      lines.add(JournalLine.debit(entryId, 1, shrinkageCode, magnitude));
      lines.add(JournalLine.credit(entryId, 2, inventoryCode, magnitude));
    } else {
      lines.add(JournalLine.debit(entryId, 1, inventoryCode, magnitude));
      lines.add(JournalLine.credit(entryId, 2, shrinkageCode, magnitude));
    }

    String description = isLoss ? "Inventory stocktake shrinkage" : "Inventory stocktake gain";
    // Derive the provisional flag from the provenance of the mappings actually resolved (INVENTORY
    // +
    // INVENTORY_SHRINKAGE) rather than hardcoding it: once V50's OFFICIAL role_account_map versions
    // resolve, a new stocktake posting is no longer badged illustrative.
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(
            occurredAt, AccountRole.INVENTORY, AccountRole.INVENTORY_SHRINKAGE);
    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        description,
        currencyCode,
        event.eventId(),
        usesIllustrative,
        lines);
  }

  /** Fail loud on an unmapped role (V50 seeds both, effective 2000-01-01 — internal fault). */
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
