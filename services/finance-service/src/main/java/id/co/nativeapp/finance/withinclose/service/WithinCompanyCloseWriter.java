package id.co.nativeapp.finance.withinclose.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.finance.consolidation.messaging.ConsolidationClosedSchema;
import id.co.nativeapp.finance.group.service.MemberGroupResolver;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedSchema;
import id.co.nativeapp.finance.withinclose.domain.WithinCompanyClose;
import id.co.nativeapp.finance.withinclose.dto.WithinCompanyCloseResult;
import id.co.nativeapp.finance.withinclose.repository.WithinCompanyCloseRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that runs a company's WITHIN-COMPANY close for a
 * period (P3d SEAM 4a — THE PRODUCERS). It finalises the company's own period and EMITS the
 * loop-closing events, all atomic with the close (rule 3):
 *
 * <ol>
 *   <li>gather the company's BALANCED trial balance from the dimensional ledger (its REVENUE +
 *       EXPENSE lines by GL account PLUS the balancing retained-earnings EQUITY closing line, so
 *       the lines sum signed-to-zero in the functional currency — {@link TrialBalanceReader});
 *   <li>persist the {@link WithinCompanyClose} row (its {@code UNIQUE (company, period)} is the
 *       idempotency guard);
 *   <li>emit {@code ConsolidationClosed(company_id = the company, group_id = NULL, period)} via the
 *       outbox — the within-company close kind notification-service consumes;
 *   <li>emit one {@code TrialBalancePublished(company_id = the company, group_id, period,
 *       base_currency, reconciled = true, uses_illustrative_rules, the balanced lines)} via the
 *       outbox PER GROUP the company is active in at period-end (resolved from the member→group
 *       reverse index — {@link MemberGroupResolver}). A company in N groups emits N trial balances;
 *       a company in no group emits none (it still emits {@code ConsolidationClosed}).
 * </ol>
 *
 * <p>A distinct bean (not a private method on {@link WithinCompanyCloseService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets {@code
 * app.current_tenant} (the closing company) the RLS {@code WITH CHECK} needs (rule 5).
 *
 * <p><strong>Idempotency.</strong> The close probes {@code within_company_close} first; an
 * already-closed {@code (company, period)} is a clean no-op that re-emits NOTHING — a re-close
 * cannot double-fire the events. (A held/incomplete state never reaches here: a within-company
 * close always reconciles by construction, so there is no PENDING analogue to guard.)
 */
@Component
public class WithinCompanyCloseWriter {

  private static final Logger log = LoggerFactory.getLogger(WithinCompanyCloseWriter.class);

  private final TrialBalanceReader trialBalanceReader;
  private final WithinCompanyCloseRepository closeRepository;
  private final MemberGroupResolver memberGroupResolver;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public WithinCompanyCloseWriter(
      TrialBalanceReader trialBalanceReader,
      WithinCompanyCloseRepository closeRepository,
      MemberGroupResolver memberGroupResolver,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.trialBalanceReader = trialBalanceReader;
    this.closeRepository = closeRepository;
    this.memberGroupResolver = memberGroupResolver;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Runs the within-company close for {@code (the bound company, period)}. The base currency is
   * DERIVED from the company's ledger (CLAUDE.md — immutable, set at company creation); {@code
   * declaredCurrency} is an OPTIONAL cross-check only.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @param declaredCurrency an optional caller-supplied ISO-4217 currency, cross-checked against
   *     the ledger-derived base currency (a mismatch fails loud); never the authoritative source.
   *     May be {@code null}.
   * @return the {@link WithinCompanyCloseResult}
   */
  @Transactional
  public WithinCompanyCloseResult close(String period, String declaredCurrency) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    // Serialize concurrent closes of THIS (company, period) with a deterministic transaction-scoped
    // advisory lock, taken BEFORE the idempotency probe below. Two parallel close requests for the
    // same (company, period) would otherwise both pass the "no row yet" probe, both gather the
    // trial
    // balance, and then one would win the uq_within_company_close UNIQUE insert while the other
    // died
    // with a unique-violation 500. The lock makes the second attempt block until the first commits,
    // then fall through to the probe, find the row, and return the clean idempotent no-op. The lock
    // auto-releases at commit/rollback. (Mirrors the labor/group-close advisory-lock primitive.)
    closeRepository.lockPeriod(tenant + ":" + period);

    // IDEMPOTENCY: an already-closed (company, period) is a clean no-op — re-emit nothing.
    Optional<WithinCompanyClose> existing = closeRepository.findByPeriod(period);
    if (existing.isPresent()) {
      WithinCompanyClose prior = existing.get();
      log.info(
          "Within-company close company={} period={} already closed (closeId={}); idempotent no-op",
          companyId,
          period,
          prior.getId());
      return new WithinCompanyCloseResult(
          prior.getId(),
          period,
          false,
          prior.isReconciled(),
          prior.isUsesIllustrativeRules(),
          List.of());
    }

    // (1) Gather the BALANCED trial balance (P&L lines + the balancing retained-earnings equity
    // line). The reader DERIVES the authoritative base currency from the ledger and cross-checks
    // the
    // optional declared currency (a mismatch fails loud — CLAUDE.md immutable base-currency rule).
    CompanyTrialBalance trialBalance = trialBalanceReader.gather(period, declaredCurrency);
    String baseCurrency = trialBalance.baseCurrency();
    boolean usesIllustrative = trialBalance.usesIllustrativeRules();
    // A within-company close always reconciles: the closing equity line makes the signed
    // double-entry
    // residual zero by construction. So reconciled is always true on the wire (the SEAM-3b native
    // balance gate then passes for this member).
    boolean reconciled = true;

    // (2) Persist the close row (its UNIQUE (company, period) is the idempotency guard).
    WithinCompanyClose close =
        new WithinCompanyClose(period, baseCurrency, reconciled, usesIllustrative);
    close.setCompanyId(tenant);
    closeRepository.save(close);
    closeRepository.flush();

    // (3) Emit ConsolidationClosed(group_id = NULL) — the within-company close kind.
    GenericRecord consolidationClosed = ConsolidationClosedSchema.toRecord(companyId, null, period);
    outboxWriter.write(
        ConsolidationClosedSchema.AGGREGATE_TYPE,
        companyId.toString(),
        ConsolidationClosedSchema.EVENT_TYPE,
        AvroSerde.serialize(consolidationClosed),
        null,
        companyId,
        clock.instant());

    // (4) Emit one TrialBalancePublished per group the company is ACTIVE in at period-end. A
    // company
    //     in no group emits none (it still did a within-company close + ConsolidationClosed above).
    List<UUID> groups =
        memberGroupResolver.activeGroupsOf(companyId, YearMonth.parse(period).atEndOfMonth());
    if (!trialBalance.isEmpty()) {
      for (UUID groupId : groups) {
        GenericRecord trialBalancePublished =
            TrialBalancePublishedSchema.toRecord(
                companyId,
                groupId,
                period,
                baseCurrency,
                reconciled,
                usesIllustrative,
                trialBalance.lines());
        // Partition key = group_id (the outbox aggregate_id), so a group's member trial balances
        // are
        // ordered after its GroupDefined, exactly as the SEAM-2 ingest expects.
        outboxWriter.write(
            TrialBalancePublishedSchema.AGGREGATE_TYPE,
            groupId.toString(),
            TrialBalancePublishedSchema.EVENT_TYPE,
            AvroSerde.serialize(trialBalancePublished),
            null,
            companyId,
            clock.instant());
      }
    } else if (!groups.isEmpty()) {
      log.info(
          "Within-company close company={} period={} has no ledger postings; emitting"
              + " ConsolidationClosed but NO TrialBalancePublished for its {} group(s)",
          companyId,
          period,
          groups.size());
    }

    List<UUID> publishedGroups = trialBalance.isEmpty() ? List.of() : groups;
    log.info(
        "Within-company close company={} period={} CLOSED: {} trial-balance line(s),"
            + " TrialBalancePublished emitted for {} group(s) {}, illustrative={}",
        companyId,
        period,
        trialBalance.lines().size(),
        publishedGroups.size(),
        publishedGroups,
        usesIllustrative);

    return new WithinCompanyCloseResult(
        close.getId(), period, true, reconciled, usesIllustrative, publishedGroups);
  }
}
