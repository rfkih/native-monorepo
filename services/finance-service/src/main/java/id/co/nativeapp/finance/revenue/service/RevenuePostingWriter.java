package id.co.nativeapp.finance.revenue.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
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

  /**
   * Atomic accumulate for the {@code outlet_revenue} per-outlet read model: insert a fresh
   * accumulator for a {@code (company_id, business_id, period, currency)} or, if one already
   * exists, add this posting's net-revenue minor units onto the stored total in a single statement.
   * Same concurrency guarantee as {@link #UPSERT_REVENUE_SQL} — no read-modify-write window. RLS
   * {@code WITH CHECK} binds {@code company_id} on the insert path.
   */
  private static final String UPSERT_OUTLET_REVENUE_SQL =
      """
      INSERT INTO outlet_revenue
          (id, business_id, period, revenue_minor, currency,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, business_id, period, currency) DO UPDATE SET
          revenue_minor = outlet_revenue.revenue_minor + EXCLUDED.revenue_minor,
          updated_at    = now(),
          updated_by    = EXCLUDED.updated_by,
          version       = outlet_revenue.version + 1
      """;

  private final LedgerPostingRepository ledgerRepository;
  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;
  private final GlAccountResolver glAccountResolver;
  private final PnlReadModelWriter pnlReadModel;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final ErrorInboxWriter errorInbox;

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RevenuePostingWriter.class);

  @SuppressWarnings("checkstyle:ParameterNumber")
  public RevenuePostingWriter(
      LedgerPostingRepository ledgerRepository,
      ProcessedEventStore processedEvents,
      JdbcTemplate jdbcTemplate,
      GlAccountResolver glAccountResolver,
      PnlReadModelWriter pnlReadModel,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      ErrorInboxWriter errorInbox) {
    this.ledgerRepository = ledgerRepository;
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
    this.glAccountResolver = glAccountResolver;
    this.pnlReadModel = pnlReadModel;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.errorInbox = errorInbox;
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
    // Phase 2: assert the reconciliation identity FIRST. A violated identity is a poison event
    // from a misconfigured producer → DLT (the transaction rolls back before writing anything).
    event.assertReconciliationIdentity();

    Money amount = event.amount();
    String period = LedgerPosting.periodOf(event.occurredAt());
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    // SEALED-PERIOD QUARANTINE (ADR 0028 security review). Offline replay made occurred_at
    // client-influenced (bounded to 48h AND the current month at the producer), so a misdated or
    // malicious SaleRecorded could still target a period whose VAT return is already FILED
    // (tax_filing seals it — ADR 0017; re-file is an idempotent no-op, so late output VAT would
    // never be remitted). Defense in depth behind the producer clamp: post NOTHING and record the
    // event to the error inbox for manual accountant action. The event is still marked processed
    // (processOnce wraps this handler), so redelivery cannot retry it into the sealed books.
    if (ledgerRepository.sealedPeriodExists(period)) {
      log.warn(
          "SaleRecorded {} targets sealed period {} — quarantined, not posted",
          event.eventId(),
          period);
      errorInbox.record(
          new IllegalStateException(
              "SaleRecorded "
                  + event.eventId()
                  + " occurred_at "
                  + event.occurredAt()
                  + " targets sealed period "
                  + period
                  + " — quarantined for manual posting"),
          "finance.revenue.sealed-period-quarantine",
          companyId,
          null);
      return;
    }

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

    // 3) Phase 2 READ-MODEL FIX: accumulate NET revenue (subtotal − discount), NOT the grand
    //    total. The grand total includes service charge and tax — accumulating it as revenue would
    //    double-count those components on the P&L. Legacy events (no breakdown) fall back to
    //    subtotal == amount, which preserves Phase 1 behaviour exactly.
    //
    //    Phase 4 (ADR 0027): loyalty-points redemption is ALSO a contra-revenue deduction — exactly
    //    like discount — so it joins the net-revenue subtraction. A pre-Phase-4 event has
    //    loyaltyRedeemedMinor == null, so effectiveLoyaltyRedeemed() == 0 and this reduces to
    //    EXACTLY the Phase 2 formula (regression-safe). gift_card_redeemed_minor never appears here
    //    — a gift-card redemption is a TENDER settlement, not a revenue deduction.
    //
    //    netRevenue = subtotal − discount − loyaltyRedeemed (= taxableBase in the pricing formula).
    Money subtotal = event.effectiveSubtotal();
    Money discount = event.effectiveDiscount();
    Money loyaltyRedeemed = event.effectiveLoyaltyRedeemed();
    Money netRevenue = subtotal.minus(discount).minus(loyaltyRedeemed);
    String currencyCode = amount.currency().getCurrencyCode();

    // 3a) consolidated_revenue read model: accumulate the NET revenue (subtotal − discount −
    //     loyaltyRedeemed).
    jdbcTemplate.update(
        UPSERT_REVENUE_SQL,
        UUID.randomUUID(),
        period,
        netRevenue.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3a-ii) outlet_revenue read model: accumulate the same NET revenue per (business_id, period).
    //        Keyed on (company_id, business_id, period, currency) — one accumulator per outlet per
    //        period. Runs in the SAME transaction as the consolidated_revenue upsert and the
    //        processOnce claim, so the outlet slice is always consistent with the consolidated
    // total.
    jdbcTemplate.update(
        UPSERT_OUTLET_REVENUE_SQL,
        UUID.randomUUID(),
        event.businessId(),
        period,
        netRevenue.amountMinor(),
        currencyCode,
        actor,
        actor,
        companyId);

    // 3b) consolidated_pnl REVENUE leg: accumulate NET revenue, with the illustrative flag from
    //     the event OR'd in (sticky: once illustrative, always illustrative for that period).
    pnlReadModel.addRevenue(
        period, netRevenue, companyId, actor, event.effectiveUsesIllustrative());

    // 4) Double-entry GL journal — SAME transaction, SAME processOnce claim. Use the Phase 2/4
    //    breakdown-aware builder so the effective SALE template's GROSS_REVENUE / DISCOUNT /
    //    SERVICE_CHARGE / TAX / NET_TENDER / GIFT_CARD_TENDER / LOYALTY_REDEEMED basis values
    //    resolve correctly (V37 SALE v3 becomes the effective template once this Java change and
    //    the migration are deployed together). Zero-amount lines are omitted. The clearing account
    //    is routed by tender type (ADR 0006 slice 2); a gift-card-covered residual splits the
    //    clearing debit onto GIFT_CARD_LIABILITY via the template, not this override.
    AccountRole clearingRole = resolveClearingRole(event.tenderType());
    JournalEntry glEntry =
        journalPostingService.buildEntryForSale(
            EventKind.SALE,
            period,
            event.occurredAt(),
            event.eventId(),
            "SaleRecorded",
            event,
            clearingRole == AccountRole.CASH_CLEARING ? null : clearingRole);
    glEntry.setCompanyId(companyId);
    // Phase 2: link the SALE journal entry to the sale aggregate so ReversalPostingWriter can
    // look up the original lines by saleId for per-leg void/refund unwind (V18).
    if (event.saleId() != null) {
      glEntry.setSaleAggregateId(event.saleId());
    }
    // Phase 2 V19 (extended Phase 4, ADR 0027): store the precomputed net revenue (subtotal −
    // discount − loyaltyRedeemed) on the SALE entry so the reversal writer can unwind the read
    // models by exactly the same amount that was accumulated — without account-code-dependent line
    // inspection (which would be fragile).
    glEntry.setNetRevenueMinor(netRevenue.amountMinor());
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

  /**
   * Resolves the GL clearing {@link AccountRole} from a {@code tender_type} wire value (ADR 0006
   * slice 2). Returns {@link AccountRole#CASH_CLEARING} for {@code null} (legacy / no-tender sales,
   * carwash) and for {@code "CASH"}; {@link AccountRole#QRIS_CLEARING} for {@code "QRIS"}; {@link
   * AccountRole#CARD_CLEARING} for {@code "CARD"}. Any unrecognised value falls back to {@link
   * AccountRole#CASH_CLEARING} (safe default — money is never dropped).
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
