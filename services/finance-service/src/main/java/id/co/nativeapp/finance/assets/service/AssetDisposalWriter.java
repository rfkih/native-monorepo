package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.domain.AssetAlreadyDisposedException;
import id.co.nativeapp.finance.assets.domain.AssetDepreciationBehindException;
import id.co.nativeapp.finance.assets.domain.AssetNotFoundException;
import id.co.nativeapp.finance.assets.domain.FixedAsset;
import id.co.nativeapp.finance.assets.domain.IdempotencyKeyConflictException;
import id.co.nativeapp.finance.assets.repository.AmortizationRunRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for disposing (selling/scrapping) a {@link FixedAsset}
 * (ADR 0022). Posts the derecognition entry — {@code Dr CASH_CLEARING} proceeds + {@code Dr
 * ACCUMULATED_DEPRECIATION} accumulated / {@code Cr FIXED_ASSET_COST} cost, plugged with {@code Cr
 * GAIN_ON_DISPOSAL} or {@code Dr LOSS_ON_DISPOSAL} — and freezes the disposal facts onto the asset
 * row in the same transaction. Ad-hoc balanced entry (the Bank/Tax/acquire path).
 *
 * <p><strong>The entry posts into the CURRENT period</strong> ({@code periodOf(now)}) — the
 * acquisition precedent. The user's {@code disposalDate} is metadata: backdating the posting would
 * restate closed/VAT-filed periods, and could even show a negative 1500 when the acquisition entry
 * itself posted later than its acquisition date.
 *
 * <p><strong>Serialization:</strong> takes the shared {@code company:ASSET_POSTING} advisory lock
 * FIRST (the amortization run takes the same lock first, same order → no deadlock) so a dispose can
 * never interleave with a run posting this asset's depreciation — the frozen {@code
 * accumulated_disposed_minor} is therefore always exactly what the entry derecognized, which the
 * cash-flow reclassification depends on. {@code source_event_id} is derived deterministically from
 * the asset id, so the {@code journal_entry} UNIQUE is a DB-level one-disposal-per-asset backstop.
 */
@Component
public class AssetDisposalWriter {

  private final FixedAssetRepository assetRepository;
  private final AmortizationRunRepository runRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public AssetDisposalWriter(
      FixedAssetRepository assetRepository,
      AmortizationRunRepository runRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Disposes an asset: posts the derecognition entry and freezes the disposal facts. Disposing
   * posts money, so it is idempotent per {@code (company, Idempotency-Key)}: a retried request
   * replays the original disposal ({@code created == false}), posting nothing.
   *
   * @param proceedsMinor the sale price in minor units ({@code 0} = scrap/write-off)
   * @return the asset id + whether this call freshly disposed it
   * @throws AssetNotFoundException if the asset does not exist in this tenant — 404
   * @throws AssetAlreadyDisposedException if already disposed under a different key — 409
   * @throws AssetDepreciationBehindException if depreciation is out of step with the posting period
   *     (gap months un-run, or already depreciated in/after the posting period) — 409
   * @throws IllegalArgumentException on invalid bounds (date/currency/proceeds) — 400
   * @throws MismatchedPostingCurrencyException if the currency diverges from the asset or the
   *     period's GL — 422
   */
  @Transactional
  public DisposeAssetResult dispose(
      UUID assetId,
      LocalDate disposalDate,
      long proceedsMinor,
      String currencyCode,
      String idempotencyKey) {
    Objects.requireNonNull(assetId, "assetId");
    Objects.requireNonNull(disposalDate, "disposalDate");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");

    String companyId = TenantContext.require().companyId();

    // The shared asset-posting lock, FIRST (see class doc) — then the replay probe under it.
    runRepository.lockPeriod(companyId + ":ASSET_POSTING");

    Optional<FixedAsset> replayed = assetRepository.findByDisposalIdempotencyKey(idempotencyKey);
    if (replayed.isPresent()) {
      // The path id is authoritative (code-review W1): a key reused against a DIFFERENT asset must
      // not silently replay the other asset's disposal — the caller's command never ran.
      if (!replayed.get().getId().equals(assetId)) {
        throw new IdempotencyKeyConflictException(assetId, replayed.get().getId());
      }
      return new DisposeAssetResult(replayed.get().getId(), false);
    }

    FixedAsset asset =
        assetRepository.findById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
    if (asset.getStatus() == FixedAsset.Status.DISPOSED) {
      // The replay probe missed, so this key is NOT the one that disposed it — a second attempt.
      throw new AssetAlreadyDisposedException(assetId);
    }

    // Bound the date (W-2 pattern) and pin it to the asset's life.
    if (disposalDate.getYear() < 1900 || disposalDate.getYear() > 9999) {
      throw new IllegalArgumentException(
          "disposalDate year must be between 1900 and 9999: " + disposalDate);
    }
    if (disposalDate.isBefore(asset.getAcquisitionDate())) {
      throw new IllegalArgumentException(
          "disposalDate must not precede the acquisition date "
              + asset.getAcquisitionDate()
              + ": "
              + disposalDate);
    }

    Currency currency = Currency.getInstance(currencyCode); // validates ISO-4217 (→ 400)
    Instant now = clock.instant();
    String postingPeriod = LedgerPosting.periodOf(now);
    if (!currency.getCurrencyCode().equals(asset.getCurrency())) {
      throw new MismatchedPostingCurrencyException(
          postingPeriod, asset.getCurrency(), currency.getCurrencyCode());
    }
    Money proceeds = Money.ofMinor(proceedsMinor, currency);
    if (proceeds.isNegative()) {
      throw new IllegalArgumentException("proceeds must be non-negative: " + proceeds);
    }
    requireConsistentGlCurrency(postingPeriod, proceeds);

    requireDepreciationInStep(asset, postingPeriod);

    Money cost = Money.ofMinor(asset.getCostMinor(), currency);
    Money accumulated = Money.ofMinor(accumulatedFor(assetId), currency);

    UUID entryId = UUID.randomUUID();
    UUID sourceEventId = disposalEventId(assetId);
    JournalEntry entry =
        buildDisposalEntry(postingPeriod, now, entryId, sourceEventId, cost, accumulated, proceeds);
    persistEntry(entry, companyId);

    asset.markDisposed(disposalDate, postingPeriod, proceeds, accumulated, entryId, idempotencyKey);
    assetRepository.save(asset);
    return new DisposeAssetResult(assetId, true);
  }

  /** The deterministic disposal {@code source_event_id} — one disposal per asset, DB-enforced. */
  public static UUID disposalEventId(UUID assetId) {
    return UUID.nameUUIDFromBytes((assetId + ":DISPOSE").getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The depreciation-complete pair (ADR 0022). Runs may skip months (periods are independent), so
   * company-wide {@code MAX(period)} proves nothing; instead: (a) the asset's posted run-line COUNT
   * must equal the months due strictly BEFORE the posting period — exact because a run writes a
   * line even for a zero-amount month; (b) no line may exist in or after the posting period (the
   * disposal month gets no depreciation, and an already-posted later month would need a reversal).
   */
  private void requireDepreciationInStep(FixedAsset asset, String postingPeriod) {
    int expected =
        Math.clamp(
            DepreciationSchedule.monthIndex(asset.getStartPeriod(), postingPeriod) - 1L,
            0,
            asset.getUsefulLifeMonths());
    Long posted =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM amortization_run_line WHERE item_type = 'ASSET' AND item_id = ?",
            Long.class,
            asset.getId());
    long postedCount = posted == null ? 0 : posted;
    if (postedCount < expected) {
      throw new AssetDepreciationBehindException(
          "asset "
              + asset.getId()
              + " has "
              + postedCount
              + " of "
              + expected
              + " due depreciation months posted — run the missing amortization periods before "
              + "disposing");
    }
    Long overshoot =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM amortization_run_line arl
              JOIN amortization_run ar ON ar.id = arl.run_id
             WHERE arl.item_type = 'ASSET' AND arl.item_id = ? AND ar.period >= ?
            """,
            Long.class,
            asset.getId(),
            postingPeriod);
    if (overshoot != null && overshoot > 0) {
      throw new AssetDepreciationBehindException(
          "asset "
              + asset.getId()
              + " already has depreciation posted in or after "
              + postingPeriod
              + " — disposing now would require a reversal, which this flow does not do");
    }
  }

  /** The accumulated depreciation to derecognize — Σ posted run-line amounts (RLS-scoped). */
  private long accumulatedFor(UUID assetId) {
    Long sum =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_minor), 0) FROM amortization_run_line "
                + "WHERE item_type = 'ASSET' AND item_id = ?",
            Long.class,
            assetId);
    return sum == null ? 0 : sum;
  }

  /**
   * Builds (but does not persist) the balanced disposal entry: {@code Dr CASH_CLEARING} proceeds
   * (skipped when 0) + {@code Dr ACCUMULATED_DEPRECIATION} accumulated (skipped when 0) / {@code Cr
   * FIXED_ASSET_COST} cost, plugged with {@code Cr GAIN_ON_DISPOSAL} when {@code proceeds − (cost −
   * accumulated) > 0}, {@code Dr LOSS_ON_DISPOSAL} when negative, no plug when exactly book value.
   * Always ≥2 legs (the cost credit is always present and positive). Public + pure (mocked
   * resolver, no DB) for the posting unit tests.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildDisposalEntry(
      String period,
      Instant now,
      UUID entryId,
      UUID sourceEventId,
      Money cost,
      Money accumulated,
      Money proceeds) {
    cost.requireSameCurrencyAs(accumulated);
    cost.requireSameCurrencyAs(proceeds);
    long gainMinor =
        Math.subtractExact(
            Math.addExact(proceeds.amountMinor(), accumulated.amountMinor()), cost.amountMinor());

    List<JournalLine> lines = new ArrayList<>();
    int lineNo = 1;
    if (proceeds.isPositive()) {
      lines.add(
          JournalLine.debit(
              entryId, lineNo++, requireMapped(AccountRole.CASH_CLEARING, now), proceeds));
    }
    if (accumulated.isPositive()) {
      lines.add(
          JournalLine.debit(
              entryId,
              lineNo++,
              requireMapped(AccountRole.ACCUMULATED_DEPRECIATION, now),
              accumulated));
    }
    lines.add(
        JournalLine.credit(
            entryId, lineNo++, requireMapped(AccountRole.FIXED_ASSET_COST, now), cost));
    if (gainMinor > 0) {
      lines.add(
          JournalLine.credit(
              entryId,
              lineNo++,
              requireMapped(AccountRole.GAIN_ON_DISPOSAL, now),
              Money.ofMinor(gainMinor, cost.currency())));
    } else if (gainMinor < 0) {
      lines.add(
          JournalLine.debit(
              entryId,
              lineNo,
              requireMapped(AccountRole.LOSS_ON_DISPOSAL, now),
              Money.ofMinor(Math.negateExact(gainMinor), cost.currency())));
    }
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Fixed asset disposed",
        cost.currency().getCurrencyCode(),
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
   * Rejects a disposal whose currency diverges from any journal entry already posted in the period
   * for this tenant (mirroring acquire/AR/AP/Bank/Tax). Runs under RLS on the
   * {@code @Transactional} connection.
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
