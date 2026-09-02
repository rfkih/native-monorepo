package id.co.nativeapp.finance.inventory.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.inventory.messaging.SaleCogsRecordedEvent;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
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
 * Posts perpetual COGS on a sale's recipe depletion (ADR 0067 Phase C, §1) — the {@code
 * SaleCogsRecorded} consumer's ONLY money.
 *
 * <ul>
 *   <li><strong>Perpetual-ACTIVE</strong> ({@link PerpetualInventoryReader#isActiveFor}, keyed on
 *       {@code occurred_at}'s period): {@code Dr COGS (5100, cogs_minor) / Cr INVENTORY (1100,
 *       cogs_minor)} — an ad-hoc 2-line entry via {@link RoleAccountResolver} (the V50 stocktake /
 *       bank / asset-disposal precedent, mirrored from the {@code StockReceivedWriter} Phase B
 *       template — no posting template needed).
 *   <li><strong>INACTIVE</strong> (every tenant today — no {@code inventory_method_config} row
 *       exists yet): a CLAIMED NO-OP. The event id is recorded processed; nothing posts. This
 *       branch is what EVERY production tenant takes today (the ADR 0067 DORMANT invariant).
 * </ul>
 *
 * <p>Unlike {@code StockReceivedWriter} (a balance-sheet-only posting), the {@code COGS} leg is a
 * genuine P&amp;L expense, so — same transaction — a dimensional {@code LedgerPosting(EXPENSE,
 * businessId, …, 5100)} is also written (the per-outlet rollup {@code ledger_posting} still backs,
 * ADR 0065 "Out of scope"). It does NOT feed {@code consolidated_pnl}: the dashboard P&amp;L is
 * GL-derived (ADR 0065) — the journal debit above reaches the beranda AND the income statement
 * automatically, so writing to the accumulator here would be exactly the "forgot to feed
 * consolidated_pnl" anti-pattern ADR 0065 closed (moot anyway, since it would be one more writer to
 * remember, not fewer).
 *
 * <p>Idempotency: {@link ProcessedEventStore#processOnce} claims the event UUID inside THIS
 * transaction; {@code journal_entry.source_event_id} is the UNIQUE DB backstop (the {@code
 * sale_id}-as-idempotency-key ADR 0067 §1 describes is realised via {@code event.eventId()} — the
 * outbox row's own UUID, one-to-one with the producing {@code sale} row's COGS fold).
 *
 * <p><strong>ADR 0067 Phase D, D2 — sealed-period symmetry.</strong> Gated the SAME way as the
 * {@code RevenuePostingWriter}/{@code RegisterCloseWriter}/{@code StocktakeWriter} consumers: for
 * an ACTIVE tenant, a sale dated into a period whose VAT return is already FILED ({@code
 * tax_filing} — ADR 0017 seals it) is QUARANTINED — nothing posts (no journal entry, no dimensional
 * {@code ledger_posting}), the event is recorded to the error inbox for manual accountant action,
 * and {@code processOnce} still marks it processed so a redelivery can never retry it into the
 * sealed books. Checked AFTER the perpetual-active branch — an INACTIVE tenant's claimed no-op must
 * stay silent (no error-inbox noise) regardless of whether the period happens to be sealed.
 */
@Component
public class SaleCogsRecordedWriter {

  private static final Logger log = LoggerFactory.getLogger(SaleCogsRecordedWriter.class);

  private final ProcessedEventStore processedEvents;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final RoleAccountResolver roleAccountResolver;
  private final PerpetualInventoryReader perpetualInventoryReader;
  private final LedgerPostingRepository ledgerRepository;
  private final ErrorInboxWriter errorInbox;
  private final JdbcTemplate jdbcTemplate;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SaleCogsRecordedWriter(
      ProcessedEventStore processedEvents,
      GeneralLedgerWriter generalLedgerWriter,
      RoleAccountResolver roleAccountResolver,
      PerpetualInventoryReader perpetualInventoryReader,
      LedgerPostingRepository ledgerRepository,
      ErrorInboxWriter errorInbox,
      JdbcTemplate jdbcTemplate) {
    this.processedEvents = processedEvents;
    this.generalLedgerWriter = generalLedgerWriter;
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
  public boolean post(SaleCogsRecordedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> postCogs(event));
  }

  private void postCogs(SaleCogsRecordedEvent event) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String period = LedgerPosting.periodOf(event.occurredAt());

    if (!perpetualInventoryReader.isActiveFor(period)) {
      log.debug(
          "SaleCogsRecorded {} claimed as a no-op — company {} is not perpetual-active for period"
              + " {}",
          event.eventId(),
          companyId,
          period);
      return;
    }

    // Sealed-period quarantine (ADR 0067 Phase D, D2 — Phase C review W2): mirrors
    // RevenuePostingWriter/RegisterCloseWriter/StocktakeWriter EXACTLY. Without this, an active
    // tenant's SaleCogsRecorded could post COGS into a period whose paired SaleRecorded revenue is
    // already quarantined — a one-sided sealed-book entry.
    if (ledgerRepository.sealedPeriodExists(period)) {
      log.warn(
          "SaleCogsRecorded {} targets sealed period {} — quarantined, not posted",
          event.eventId(),
          period);
      errorInbox.record(
          new IllegalStateException(
              "SaleCogsRecorded "
                  + event.eventId()
                  + " occurred_at "
                  + event.occurredAt()
                  + " targets sealed period "
                  + period
                  + " — cogs "
                  + event.cogsMinor()
                  + " "
                  + event.currency()
                  + " quarantined for manual posting"),
          "finance.inventory.sale-cogs-recorded-sealed-period-quarantine",
          companyId,
          null);
      return;
    }

    Money cogs = Money.ofMinor(event.cogsMinor(), event.currency());
    if (cogs.isZero()) {
      // The producer only ever emits when its fold is strictly positive (sale.cogs_minor
      // both-or-neither CHECK, V44); assertValid already rejects a non-positive cogs_minor as
      // poison. This is defence-in-depth only, mirroring StockReceivedWriter's zero-value guard.
      log.debug("SaleCogsRecorded {} carries a zero cogs_minor — no entry", event.eventId());
      return;
    }
    requireConsistentGlCurrency(period, cogs);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(event, entryId, period);
    generalLedgerWriter.post(entry, companyId);

    // Dimensional posting (§1): the per-outlet P&L rollup reads ledger_posting — the dashboard P&L
    // is GL-derived (ADR 0065), so the journal debit above already reaches the beranda AND the
    // income statement automatically. No consolidated_pnl write here (see class javadoc).
    String cogsCode = requireMapped(AccountRole.COGS, event.occurredAt());
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE, event.businessId(), period, cogs, cogsCode, event.eventId());
    posting.setCompanyId(companyId);
    ledgerRepository.save(posting);

    log.info("Posted perpetual COGS for sale {} (entry {})", event.saleId(), entryId);
  }

  /**
   * Builds (but does not persist) the balanced COGS entry: {@code Dr COGS / Cr INVENTORY}. Public +
   * pure besides the resolver lookups so a unit test can assert the exact legs (the {@code
   * StockReceivedWriter#buildEntry} pattern).
   *
   * @throws IllegalStateException if {@code cogsMinor} is zero (a balanced entry cannot have a
   *     zero-magnitude line — the caller must never build an entry for a costless sale)
   */
  public JournalEntry buildEntry(SaleCogsRecordedEvent event, UUID entryId, String period) {
    if (event.cogsMinor() == 0L) {
      throw new IllegalStateException(
          "cogs_minor is zero — no COGS entry should be built for a costless sale");
    }
    Money cogs = Money.ofMinor(event.cogsMinor(), event.currency());
    String cogsCode = requireMapped(AccountRole.COGS, event.occurredAt());
    String inventoryCode = requireMapped(AccountRole.INVENTORY, event.occurredAt());

    List<JournalLine> lines = new ArrayList<>(2);
    lines.add(JournalLine.debit(entryId, 1, cogsCode, cogs));
    lines.add(JournalLine.credit(entryId, 2, inventoryCode, cogs));

    return JournalEntry.balanced(
        entryId,
        period,
        event.occurredAt(),
        "Perpetual COGS on sale",
        event.currency(),
        event.eventId(),
        true, // COGS/INVENTORY are illustrative-seeded (V53) — always provisional today
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
