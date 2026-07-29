package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.domain.AmortizationRun;
import id.co.nativeapp.finance.assets.domain.AmortizationRunLine;
import id.co.nativeapp.finance.assets.domain.Deferral;
import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.domain.FixedAsset;
import id.co.nativeapp.finance.assets.repository.AmortizationRunLineRepository;
import id.co.nativeapp.finance.assets.repository.AmortizationRunRepository;
import id.co.nativeapp.finance.assets.repository.DeferralRepository;
import id.co.nativeapp.finance.assets.repository.FixedAssetRepository;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for the monthly AMORTIZATION RUN (Phase 6, ADR 0020) —
 * the system's first time-based scheduled posting, and the money-critical novel piece of the phase.
 * One call posts EVERY due depreciation/amortization for {@code (the bound company, period)}:
 *
 * <ul>
 *   <li>each ACTIVE {@link FixedAsset} due in the period (month {@code k} of its life): {@code Dr
 *       DEPRECIATION_EXPENSE (5500) / Cr ACCUMULATED_DEPRECIATION (1590)};
 *   <li>each {@link Deferral} due: prepaid → {@code Dr EXPENSE (5000) / Cr PREPAID_EXPENSE (1400)};
 *       deferred revenue → {@code Dr DEFERRED_REVENUE (2400) / Cr REVENUE (4000)}.
 * </ul>
 *
 * <p>Amounts come from {@link DepreciationSchedule#amountForMonth} (cumulative-rounding straight
 * line — Σ over the schedule equals the base EXACTLY). A month whose share rounds to zero records
 * its {@link AmortizationRunLine} with no journal entry. All entries + lines + the {@link
 * AmortizationRun} seal commit in ONE transaction — a failure anywhere rolls the whole run back.
 *
 * <p><strong>Idempotency</strong> (the tax-filing seal pattern): a per-{@code (company, period)}
 * advisory lock BEFORE the {@code findByPeriod} probe (an already-run period no-ops, posting
 * nothing), the {@code uq_amortization_run} UNIQUE as the durable backstop, and each entry's {@code
 * source_event_id = its run line's id} (UNIQUE on {@code journal_entry}) as the per-item backstop.
 * The entries post INTO the run period (they belong to it), like the tax netting entry. A distinct
 * proxy-invoked bean so {@code @Transactional} + the RLS aspect engage (rule 5).
 */
@Component
public class AmortizationRunWriter {

  private final FixedAssetRepository assetRepository;
  private final DeferralRepository deferralRepository;
  private final AmortizationRunRepository runRepository;
  private final AmortizationRunLineRepository runLineRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public AmortizationRunWriter(
      FixedAssetRepository assetRepository,
      DeferralRepository deferralRepository,
      AmortizationRunRepository runRepository,
      AmortizationRunLineRepository runLineRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository");
    this.deferralRepository = Objects.requireNonNull(deferralRepository, "deferralRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.runLineRepository = Objects.requireNonNull(runLineRepository, "runLineRepository");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Runs depreciation/amortization for {@code (the bound company, period)}. An empty run (nothing
   * due) still seals the period — a valid "ran, nothing to post" record.
   *
   * @return the run id + whether it was freshly run (a re-run returns the existing id with {@code
   *     created == false}, posting nothing)
   * @throws MismatchedPostingCurrencyException if an item's currency diverges from the period's GL
   *     (→ 422; the whole run rolls back — no partial posting)
   */
  @Transactional
  public RunAmortizationResult run(String period) {
    Objects.requireNonNull(period, "period");
    String companyId = TenantContext.require().companyId();

    // Serialize concurrent runs of THIS (company, period), BEFORE the idempotency probe.
    runRepository.lockPeriod(companyId + ":" + period + ":AMORT");

    Optional<AmortizationRun> existing = runRepository.findByPeriod(period);
    if (existing.isPresent()) {
      // IDEMPOTENT no-op — the period is already run; post nothing (created == false → 200).
      return new RunAmortizationResult(existing.get().getId(), false);
    }

    Instant now = clock.instant();
    UUID runId = UUID.randomUUID();

    // Two passes: PLAN first (compute every due item's month-k amount), then PERSIST — the seal
    // header carries the plan's counts and must insert before the FK'd lines. uq_amortization_run
    // is the durable backstop for a race that slipped past the advisory lock.
    record Planned(AmortizationRunLine.ItemType type, UUID itemId, int k, Money amount) {}
    List<Planned> planned = new java.util.ArrayList<>();

    for (FixedAsset asset : assetRepository.findByStatus(FixedAsset.Status.ACTIVE)) {
      if (DepreciationSchedule.isDue(asset.getStartPeriod(), period, asset.getUsefulLifeMonths())) {
        int k = DepreciationSchedule.monthIndex(asset.getStartPeriod(), period);
        Money amount =
            DepreciationSchedule.amountForMonth(
                asset.depreciableBase(), k, asset.getUsefulLifeMonths());
        planned.add(new Planned(AmortizationRunLine.ItemType.ASSET, asset.getId(), k, amount));
      }
    }
    List<Deferral> deferrals = deferralRepository.findAll();
    for (Deferral deferral : deferrals) {
      if (DepreciationSchedule.isDue(deferral.getStartPeriod(), period, deferral.getMonths())) {
        int k = DepreciationSchedule.monthIndex(deferral.getStartPeriod(), period);
        Money amount =
            DepreciationSchedule.amountForMonth(deferral.total(), k, deferral.getMonths());
        planned.add(new Planned(AmortizationRunLine.ItemType.DEFERRAL, deferral.getId(), k, amount));
      }
    }

    // Persist: seal header first (FK target), then each line (+ its entry when the amount > 0).
    AmortizationRun run =
        AmortizationRun.of(
            runId,
            period,
            planned.size(),
            planned.stream().mapToLong(p -> p.amount().amountMinor()).reduce(0L, Math::addExact),
            now);
    run.setCompanyId(companyId);
    runRepository.saveAndFlush(run);

    for (Planned p : planned) {
      UUID lineId = UUID.randomUUID();
      UUID entryId = null;
      if (p.amount().isPositive()) {
        requireConsistentGlCurrency(period, p.amount());
        entryId = UUID.randomUUID();
        JournalEntry entry;
        if (p.type() == AmortizationRunLine.ItemType.ASSET) {
          entry = buildAssetEntry(period, now, entryId, lineId, p.amount());
        } else {
          DeferralKind kind =
              deferrals.stream()
                  .filter(d -> d.getId().equals(p.itemId()))
                  .findFirst()
                  .orElseThrow()
                  .getKind();
          entry = buildDeferralEntry(kind, period, now, entryId, lineId, p.amount());
        }
        persistEntry(entry, companyId);
      }
      AmortizationRunLine line =
          AmortizationRunLine.of(
              lineId,
              runId,
              p.type(),
              p.itemId(),
              p.k(),
              p.amount().amountMinor(),
              entryId,
              p.amount().currency().getCurrencyCode());
      line.setCompanyId(companyId);
      runLineRepository.save(line);
    }

    return new RunAmortizationResult(runId, true);
  }

  /**
   * Builds (but does not persist) the balanced monthly depreciation entry for one asset: {@code Dr
   * DEPRECIATION_EXPENSE / Cr ACCUMULATED_DEPRECIATION}. Public + pure (mocked resolver, no DB) for
   * the posting unit tests — the {@code ReconcilePostingTest} shape.
   */
  public JournalEntry buildAssetEntry(
      String period, Instant now, UUID entryId, UUID sourceEventId, Money amount) {
    String expenseCode = requireMapped(AccountRole.DEPRECIATION_EXPENSE, now);
    String accumCode = requireMapped(AccountRole.ACCUMULATED_DEPRECIATION, now);
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, expenseCode, amount),
            JournalLine.credit(entryId, 2, accumCode, amount));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Depreciation (" + period + ")",
        amount.currency().getCurrencyCode(),
        sourceEventId,
        true,
        lines);
  }

  /**
   * Builds (but does not persist) the balanced monthly amortization entry for one deferral:
   * prepaid → {@code Dr EXPENSE / Cr PREPAID_EXPENSE}; deferred revenue → {@code Dr
   * DEFERRED_REVENUE / Cr REVENUE}. Public + pure for the posting unit tests.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildDeferralEntry(
      DeferralKind kind, String period, Instant now, UUID entryId, UUID sourceEventId, Money amount) {
    List<JournalLine> lines;
    String description;
    if (kind == DeferralKind.PREPAID_EXPENSE) {
      String expenseCode = requireMapped(AccountRole.EXPENSE, now);
      String prepaidCode = requireMapped(AccountRole.PREPAID_EXPENSE, now);
      lines =
          List.of(
              JournalLine.debit(entryId, 1, expenseCode, amount),
              JournalLine.credit(entryId, 2, prepaidCode, amount));
      description = "Prepaid expense amortized (" + period + ")";
    } else {
      String deferredCode = requireMapped(AccountRole.DEFERRED_REVENUE, now);
      String revenueCode = requireMapped(AccountRole.REVENUE, now);
      lines =
          List.of(
              JournalLine.debit(entryId, 1, deferredCode, amount),
              JournalLine.credit(entryId, 2, revenueCode, amount));
      description = "Deferred revenue recognized (" + period + ")";
    }
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        description,
        amount.currency().getCurrencyCode(),
        sourceEventId,
        true,
        lines);
  }

  private String requireMapped(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for " + role + " at " + occurredAt);
    }
    return accountCode;
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    entry.setCompanyId(companyId);
    // saveAndFlush forces the journal_entry INSERT before the FK'd line INSERTs (same tx).
    journalEntryRepository.saveAndFlush(entry);
    for (var line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

  /**
   * Rejects a posting whose currency diverges from any journal entry already posted in the period
   * for this tenant (mirroring AR/AP/Bank/Tax). Runs under RLS. Because the whole run is one
   * transaction, a divergent item rolls the ENTIRE run back — no partial posting.
   */
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
