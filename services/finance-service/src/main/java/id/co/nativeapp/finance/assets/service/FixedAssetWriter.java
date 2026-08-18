package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.domain.AssetSealedPeriodException;
import id.co.nativeapp.finance.assets.domain.FixedAsset;
import id.co.nativeapp.finance.assets.repository.FixedAssetRepository;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for acquiring (capitalizing) a {@link FixedAsset} (Phase
 * 6, ADR 0020). Posts the capex entry — {@code Dr FIXED_ASSET_COST (1500) / Cr CASH_CLEARING
 * (1900)} for the cost, routed through the same clearing account every cash movement uses so a
 * later bank reconciliation sweeps it — in the same transaction as the asset row. Ad-hoc balanced
 * entry (no posting_template / EventKind — the Bank/Tax path); {@code source_event_id = the asset
 * id} (UNIQUE on {@code journal_entry}). A distinct proxy-invoked bean so {@code @Transactional} +
 * the RLS aspect engage (rule 5). Capitalizing from an AP bill is a deferred flow (ADR 0020).
 */
@Component
public class FixedAssetWriter {

  private final FixedAssetRepository assetRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public FixedAssetWriter(
      FixedAssetRepository assetRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Acquires an asset: posts the capex entry and saves the asset (start period = the month after
   * acquisition — the full-month convention, SME-gated). Acquiring posts money, so it is idempotent
   * per {@code (company, Idempotency-Key)}: a retried request replays the original asset ({@code
   * created == false}), posting nothing (code-review C-1 — the AR/AP payment rationale).
   *
   * @return the asset id + whether this call freshly acquired it
   * @throws IllegalArgumentException on invalid bounds (cost/salvage/life/currency/date, or a start
   *     period falling in an already-run month) — 400
   * @throws MismatchedPostingCurrencyException if the currency diverges from the period's GL — 422
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @Transactional
  public AcquireAssetResult acquire(
      String name,
      LocalDate acquisitionDate,
      long costMinor,
      long salvageMinor,
      int usefulLifeMonths,
      String currencyCode,
      String idempotencyKey) {
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(acquisitionDate, "acquisitionDate");

    // IDEMPOTENT replay (C-1): a retry of the same client attempt returns the original asset and
    // posts NOTHING. The uq_fixed_asset_idem UNIQUE is the DB backstop for a concurrent race.
    Optional<FixedAsset> replayed = assetRepository.findByIdempotencyKey(idempotencyKey);
    if (replayed.isPresent()) {
      return new AcquireAssetResult(replayed.get().getId(), false);
    }

    // Bound the date (code-review W-2): an absurd year would otherwise surface as a misleading
    // 409/500 (VARCHAR(7) overflow / monthIndex overflow) instead of a clear 400.
    if (acquisitionDate.getYear() < 1900 || acquisitionDate.getYear() > 9999) {
      throw new IllegalArgumentException(
          "acquisitionDate year must be between 1900 and 9999: " + acquisitionDate);
    }

    // Reject a schedule starting in (or before) an already-run month (code-review W-1): the sealed
    // run would never re-run, so the item's early months would silently never post and the schedule
    // could never sum to its base. Checked BEFORE any posting (start = acquisition month + 1).
    requireStartAfterLastRun(YearMonth.from(acquisitionDate).plusMonths(1).toString());

    Currency currency = Currency.getInstance(currencyCode); // validates ISO-4217 (→ 400)
    Money cost = Money.ofMinor(costMinor, currency);
    Money salvage = Money.ofMinor(salvageMinor, currency);

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    requireConsistentGlCurrency(period, cost);

    UUID assetId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildAcquisitionEntry(period, now, entryId, assetId, cost);
    persistEntry(entry, companyId);

    FixedAsset asset =
        FixedAsset.acquire(
            assetId,
            name,
            acquisitionDate,
            cost,
            salvage,
            usefulLifeMonths,
            entryId,
            idempotencyKey);
    asset.setCompanyId(companyId);
    assetRepository.save(asset);
    return new AcquireAssetResult(assetId, true);
  }

  /**
   * Registers a pre-owned (brought-forward) asset during business migration (ADR 0037) — the
   * cash-free counterpart to {@link #acquire}. Posts {@code Dr FIXED_ASSET_COST gross / Cr
   * ACCUMULATED_DEPRECIATION opening / Cr OPENING_BALANCE_EQUITY net} (never crediting cash: the
   * asset was bought before go-live), and registers it with its remaining life +
   * depreciation-to-date so future runs depreciate only the remaining base. Idempotent per {@code
   * (company, Idempotency-Key)} like {@link #acquire}.
   *
   * @param asOfDate the go-live / as-of date — the opening entry's period and the depreciation
   *     start
   * @param openingAccumulatedMinor depreciation already taken (0 = a newly bought asset never yet
   *     run)
   * @param remainingLifeMonths the depreciation months still to run
   * @return the asset id + whether this call freshly registered it
   * @throws IllegalArgumentException on invalid bounds — 400
   * @throws AssetSealedPeriodException if the as-of period is tax-sealed — 422
   * @throws MismatchedPostingCurrencyException if the currency diverges from the period's GL — 422
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @Transactional
  public AcquireAssetResult registerBroughtForward(
      String name,
      LocalDate asOfDate,
      long costMinor,
      long salvageMinor,
      long openingAccumulatedMinor,
      int remainingLifeMonths,
      String currencyCode,
      String idempotencyKey) {
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(asOfDate, "asOfDate");

    Optional<FixedAsset> replayed = assetRepository.findByIdempotencyKey(idempotencyKey);
    if (replayed.isPresent()) {
      return new AcquireAssetResult(replayed.get().getId(), false);
    }

    if (asOfDate.getYear() < 1900 || asOfDate.getYear() > 9999) {
      throw new IllegalArgumentException(
          "asOfDate year must be between 1900 and 9999: " + asOfDate);
    }

    // Depreciation starts the month AFTER go-live (the full-month convention, mirroring acquire).
    String startPeriod = YearMonth.from(asOfDate).plusMonths(1).toString();
    requireStartAfterLastRun(startPeriod);

    Currency currency = Currency.getInstance(currencyCode); // validates ISO-4217 (→ 400)
    Money cost = Money.ofMinor(costMinor, currency);
    Money salvage = Money.ofMinor(salvageMinor, currency);
    Money openingAccumulated = Money.ofMinor(openingAccumulatedMinor, currency);

    String companyId = TenantContext.require().companyId();
    // The opening entry posts into the AS-OF month (a real YYYY-MM), consistent with the opening
    // balance sheet it is part of; occurred_at is the as-of instant so periodOf agrees.
    Instant now = asOfDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    String period = LedgerPosting.periodOf(now);
    // Console posts brought-forward assets BEFORE the main opening entry (ADR 0037); without this
    // guard a sealed as-of date would silently restate a tax-filed month and then the main opening
    // entry would 422, stranding a half-completed migration. Checked AFTER the idempotency replay
    // (a replay of a previously-successful registration must still return 200) and BEFORE any
    // persistence — mirrors OpeningBalanceWriter#requireNotSealed.
    requireNotSealed(period);
    requireConsistentGlCurrency(period, cost);

    UUID assetId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    JournalEntry entry =
        buildBroughtForwardEntry(period, now, entryId, assetId, cost, openingAccumulated);
    persistEntry(entry, companyId);

    FixedAsset asset =
        FixedAsset.acquireBroughtForward(
            assetId,
            name,
            asOfDate,
            startPeriod,
            cost,
            salvage,
            openingAccumulated,
            remainingLifeMonths,
            entryId,
            idempotencyKey);
    asset.setCompanyId(companyId);
    assetRepository.save(asset);
    return new AcquireAssetResult(assetId, true);
  }

  /**
   * Rejects a schedule {@code startPeriod} at or before the tenant's latest amortization run: those
   * months are sealed and will never re-run, so the item would be under-amortized forever (W-1).
   * Runs under RLS on the {@code @Transactional} connection.
   */
  private void requireStartAfterLastRun(String startPeriod) {
    List<String> latest =
        jdbcTemplate.query(
            "SELECT MAX(period) FROM amortization_run", (rs, rowNum) -> rs.getString(1));
    String maxRun = latest.isEmpty() ? null : latest.getFirst();
    if (maxRun != null && startPeriod.compareTo(maxRun) <= 0) {
      throw new IllegalArgumentException(
          "the schedule would start in "
              + startPeriod
              + ", but depreciation/amortization has already been run through "
              + maxRun
              + " — an already-run month never re-runs, so the item could never fully amortize");
    }
  }

  /**
   * Builds (but does not persist) the balanced capex entry: {@code Dr FIXED_ASSET_COST / Cr
   * CASH_CLEARING} for the cost. Public + pure (mocked resolver, no DB) for the posting unit tests.
   */
  public JournalEntry buildAcquisitionEntry(
      String period, Instant now, UUID entryId, UUID sourceEventId, Money cost) {
    String assetCode = requireMapped(AccountRole.FIXED_ASSET_COST, now);
    String clearingCode = requireMapped(AccountRole.CASH_CLEARING, now);
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, assetCode, cost),
            JournalLine.credit(entryId, 2, clearingCode, cost));
    // Derived from the provenance of the FIXED_ASSET_COST/CASH_CLEARING mappings actually resolved
    // above, rather than hardcoded.
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(
            now, AccountRole.FIXED_ASSET_COST, AccountRole.CASH_CLEARING);
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Fixed asset acquired",
        cost.currency().getCurrencyCode(),
        sourceEventId,
        usesIllustrative,
        lines);
  }

  /**
   * Builds (but does not persist) the balanced, CASH-FREE brought-forward entry (ADR 0037): {@code
   * Dr FIXED_ASSET_COST gross / Cr ACCUMULATED_DEPRECIATION opening (omitted if 0) / Cr
   * OPENING_BALANCE_EQUITY net book value (omitted if 0)}. Net = gross − opening ≥ salvage ≥ 0, so
   * the OBE leg is omitted only for a fully-depreciated zero-salvage asset (then the accumulated
   * leg balances the cost). Public + pure (mocked resolver, no DB) for the posting unit tests.
   */
  public JournalEntry buildBroughtForwardEntry(
      String period,
      Instant now,
      UUID entryId,
      UUID sourceEventId,
      Money cost,
      Money openingAccumulated) {
    Money net =
        Money.ofMinor(
            Math.subtractExact(cost.amountMinor(), openingAccumulated.amountMinor()),
            cost.currency());
    List<JournalLine> lines = new java.util.ArrayList<>();
    lines.add(
        JournalLine.debit(entryId, 1, requireMapped(AccountRole.FIXED_ASSET_COST, now), cost));
    List<AccountRole> rolesPosted =
        new java.util.ArrayList<>(List.of(AccountRole.FIXED_ASSET_COST));
    int lineNo = 2;
    if (openingAccumulated.isPositive()) {
      lines.add(
          JournalLine.credit(
              entryId,
              lineNo++,
              requireMapped(AccountRole.ACCUMULATED_DEPRECIATION, now),
              openingAccumulated));
      rolesPosted.add(AccountRole.ACCUMULATED_DEPRECIATION);
    }
    if (net.isPositive()) {
      lines.add(
          JournalLine.credit(
              entryId, lineNo, requireMapped(AccountRole.OPENING_BALANCE_EQUITY, now), net));
      rolesPosted.add(AccountRole.OPENING_BALANCE_EQUITY);
    }
    // Derived from the provenance of the roles actually posted above (the conditional
    // ACCUMULATED_DEPRECIATION/OPENING_BALANCE_EQUITY legs only count when present).
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(now, rolesPosted.toArray(new AccountRole[0]));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Fixed asset brought forward",
        cost.currency().getCurrencyCode(),
        sourceEventId,
        usesIllustrative,
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
   * Rejects a brought-forward registration whose opening period is sealed — a {@code tax_filing}
   * row already exists for it. Mirrors {@code OpeningBalanceWriter#requireNotSealed} exactly (same
   * query shape). Runs under RLS on the {@code @Transactional} connection.
   */
  private void requireNotSealed(String period) {
    Boolean sealed =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tax_filing WHERE period = ?)", Boolean.class, period);
    if (Boolean.TRUE.equals(sealed)) {
      throw new AssetSealedPeriodException(
          "period "
              + period
              + " is sealed (a tax filing exists) — a brought-forward asset cannot post into it");
    }
  }

  /**
   * Rejects an acquisition whose currency diverges from any journal entry already posted in the
   * period for this tenant (mirroring AR/AP/Bank/Tax). Runs under RLS on the {@code @Transactional}
   * connection.
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
