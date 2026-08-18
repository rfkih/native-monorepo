package id.co.nativeapp.finance.inventory.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.inventory.messaging.StockReceivedEvent;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
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
 * Capitalizes a PRICED goods receipt (ADR 0067 Phase B, §1–§2) — the {@code StockReceived}
 * consumer's ONLY money.
 *
 * <ul>
 *   <li><strong>Perpetual-ACTIVE</strong> ({@link PerpetualInventoryReader#isActiveFor}, keyed on
 *       {@code received_at}'s period): {@code Dr INVENTORY (1100, value_minor) / Cr GRNI_CLEARING
 *       (2050, value_minor)} — an ad-hoc 2-line entry via {@link RoleAccountResolver} (the V50
 *       stocktake / bank / asset-disposal precedent — no posting template needed).
 *   <li><strong>INACTIVE</strong> (every tenant in Phase B — no {@code inventory_method_config} row
 *       exists yet): a CLAIMED NO-OP. The event id is recorded processed; nothing posts. This
 *       branch is what EVERY production tenant takes today (the ADR 0067 DORMANT invariant).
 * </ul>
 *
 * <p>Both legs are balance-sheet-only (an asset debit against a liability credit) — unlike a
 * stocktake shrinkage or COGS posting, a goods receipt never touches the P&amp;L, so this writer
 * does not touch {@code ledger_posting} / {@code consolidated_pnl}.
 *
 * <p>Idempotency: {@link ProcessedEventStore#processOnce} claims the event UUID inside THIS
 * transaction; {@code journal_entry.source_event_id} is the UNIQUE DB backstop (the {@code
 * receipt_id}-as-idempotency-key ADR 0067 §1 describes is realised via {@code event.eventId()} —
 * the outbox row's own UUID, one-to-one with the producing {@code goods_receipt} row).
 *
 * <p><strong>ADR 0067 Phase D, D2 — sealed-period symmetry.</strong> Gated the SAME way as the
 * {@code RevenuePostingWriter}/{@code RegisterCloseWriter}/{@code StocktakeWriter} consumers: for
 * an ACTIVE tenant, a receipt dated into a period whose VAT return is already FILED ({@code
 * tax_filing} — ADR 0017 seals it) is QUARANTINED — nothing posts, the event is recorded to the
 * error inbox for manual accountant action, and {@code processOnce} still marks it processed so a
 * redelivery can never retry it into the sealed books. Checked AFTER the perpetual-active branch —
 * an INACTIVE tenant's claimed no-op must stay silent (no error-inbox noise) regardless of whether
 * the period happens to be sealed.
 */
@Component
public class StockReceivedWriter {

  private static final Logger log = LoggerFactory.getLogger(StockReceivedWriter.class);

  private final ProcessedEventStore processedEvents;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final PerpetualInventoryReader perpetualInventoryReader;
  private final LedgerPostingRepository ledgerRepository;
  private final ErrorInboxWriter errorInbox;
  private final JdbcTemplate jdbcTemplate;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public StockReceivedWriter(
      ProcessedEventStore processedEvents,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      PerpetualInventoryReader perpetualInventoryReader,
      LedgerPostingRepository ledgerRepository,
      ErrorInboxWriter errorInbox,
      JdbcTemplate jdbcTemplate) {
    this.processedEvents = processedEvents;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
    this.perpetualInventoryReader = perpetualInventoryReader;
    this.ledgerRepository = ledgerRepository;
    this.errorInbox = errorInbox;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Claims the event id and, on first delivery, posts (or claims-no-op).
   *
   * @return true when this delivery ran the handler; false on a re-delivery
   */
  @Transactional
  public boolean post(StockReceivedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> capitalize(event));
  }

  private void capitalize(StockReceivedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String period = LedgerPosting.periodOf(event.receivedAt());

    if (!perpetualInventoryReader.isActiveFor(period)) {
      log.debug(
          "StockReceived {} claimed as a no-op — company {} is not perpetual-active for period {}",
          event.eventId(),
          companyId,
          period);
      return;
    }

    // Sealed-period quarantine (ADR 0067 Phase D, D2 — Phase C review W2): mirrors
    // RevenuePostingWriter/RegisterCloseWriter/StocktakeWriter EXACTLY. Without this, an active
    // tenant's StockReceived could capitalize into a period whose paired SaleRecorded/COGS is
    // already quarantined — a one-sided sealed-book entry.
    if (ledgerRepository.sealedPeriodExists(period)) {
      log.warn(
          "StockReceived {} targets sealed period {} — quarantined, not posted",
          event.eventId(),
          period);
      errorInbox.record(
          new IllegalStateException(
              "StockReceived "
                  + event.eventId()
                  + " received_at "
                  + event.receivedAt()
                  + " targets sealed period "
                  + period
                  + " — value "
                  + event.valueMinor()
                  + " "
                  + event.currency()
                  + " quarantined for manual posting"),
          "finance.inventory.stock-received-sealed-period-quarantine",
          companyId,
          null);
      return;
    }

    Money value = Money.ofMinor(event.valueMinor(), event.currency());
    if (value.isZero()) {
      // A priced receive MAY carry amountPaidMinor = 0 (a free/sample receipt) — nothing to
      // capitalize, but the event is still claimed (the StocktakeWriter zero-shrinkage precedent).
      log.debug("StockReceived {} carries a zero value — no capitalization entry", event.eventId());
      return;
    }
    requireConsistentGlCurrency(period, value);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(event, entryId, period);
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }

    log.info("Capitalized goods receipt {} to inventory (entry {})", event.receiptId(), entryId);
  }

  /**
   * Builds (but does not persist) the balanced capitalization entry: {@code Dr INVENTORY / Cr
   * GRNI_CLEARING}. Public + pure besides the resolver lookups so a unit test can assert the exact
   * legs (the {@code StocktakeWriter#buildEntry} pattern).
   *
   * @throws IllegalStateException if {@code valueMinor} is zero (a balanced entry cannot have a
   *     zero-magnitude line — the caller must never build an entry for a valueless receipt; a
   *     PRICED receive always carries {@code amountPaidMinor >= 0}, and only a strictly positive
   *     value is worth capitalizing)
   */
  public JournalEntry buildEntry(StockReceivedEvent event, UUID entryId, String period) {
    if (event.valueMinor() == 0L) {
      throw new IllegalStateException(
          "value_minor is zero — no capitalization entry should be built for a valueless receipt");
    }
    Money value = Money.ofMinor(event.valueMinor(), event.currency());
    String inventoryCode = requireMapped(AccountRole.INVENTORY, event.receivedAt());
    String grniCode = requireMapped(AccountRole.GRNI_CLEARING, event.receivedAt());

    List<JournalLine> lines = new ArrayList<>(2);
    lines.add(JournalLine.debit(entryId, 1, inventoryCode, value));
    lines.add(JournalLine.credit(entryId, 2, grniCode, value));

    return JournalEntry.balanced(
        entryId,
        period,
        event.receivedAt(),
        "Goods receipt capitalized to inventory",
        event.currency(),
        event.eventId(),
        true, // GRNI_CLEARING/INVENTORY are illustrative-seeded (V50/V53) — always provisional
        // today
        lines);
  }

  /** Fail loud on an unmapped role (V53 seeds both, effective 2000-01-01 — internal fault). */
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
