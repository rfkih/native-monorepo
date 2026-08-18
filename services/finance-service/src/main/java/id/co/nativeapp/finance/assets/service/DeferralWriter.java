package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.domain.Deferral;
import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.repository.DeferralRepository;
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
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for creating a {@link Deferral} (Phase 6, ADR 0020).
 * Posts the opening entry in the same transaction as the deferral row — {@code Dr PREPAID_EXPENSE
 * (1400) / Cr CASH_CLEARING (1900)} for a prepaid expense, {@code Dr CASH_CLEARING (1900) / Cr
 * DEFERRED_REVENUE (2400)} for deferred revenue — ad-hoc balanced (the Bank/Tax path), {@code
 * source_event_id = the deferral id} (UNIQUE). The monthly amortization run then posts each month's
 * share to the P&amp;L. A distinct proxy-invoked bean so {@code @Transactional} + the RLS aspect
 * engage (rule 5).
 */
@Component
public class DeferralWriter {

  private final DeferralRepository deferralRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public DeferralWriter(
      DeferralRepository deferralRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.deferralRepository = Objects.requireNonNull(deferralRepository, "deferralRepository");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Creates a deferral: posts the opening entry and saves the deferral. Creating posts money, so it
   * is idempotent per {@code (company, Idempotency-Key)}: a retried request replays the original
   * deferral ({@code created == false}), posting nothing (code-review C-1).
   *
   * @return the deferral id + whether this call freshly created it
   * @throws IllegalArgumentException on invalid bounds (total/months/currency, or a start period
   *     falling in an already-run month) — 400
   * @throws MismatchedPostingCurrencyException if the currency diverges from the period's GL — 422
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @Transactional
  public CreateDeferralResult create(
      DeferralKind kind,
      String description,
      long totalMinor,
      int months,
      String startPeriod,
      String currencyCode,
      String idempotencyKey) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(startPeriod, "startPeriod");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");

    // IDEMPOTENT replay (C-1): a retry of the same client attempt returns the original deferral and
    // posts NOTHING. The uq_deferral_idem UNIQUE is the DB backstop for a concurrent race.
    Optional<Deferral> replayed = deferralRepository.findByIdempotencyKey(idempotencyKey);
    if (replayed.isPresent()) {
      return new CreateDeferralResult(replayed.get().getId(), false);
    }

    // Reject a schedule starting in (or before) an already-run month (code-review W-1): that month
    // is sealed and never re-runs, so the item's early shares would silently never post.
    requireStartAfterLastRun(startPeriod);

    Currency currency = Currency.getInstance(currencyCode); // validates ISO-4217 (→ 400)
    Money total = Money.ofMinor(totalMinor, currency);

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    requireConsistentGlCurrency(period, total);

    UUID deferralId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildCreationEntry(kind, period, now, entryId, deferralId, total);
    persistEntry(entry, companyId);

    Deferral deferral =
        Deferral.create(
            deferralId, kind, description, total, months, startPeriod, entryId, idempotencyKey);
    deferral.setCompanyId(companyId);
    deferralRepository.save(deferral);
    return new CreateDeferralResult(deferralId, true);
  }

  /**
   * Rejects a {@code startPeriod} at or before the tenant's latest amortization run (W-1 — see
   * {@code FixedAssetWriter#requireStartAfterLastRun}). Runs under RLS.
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
   * Builds (but does not persist) the balanced opening entry for a deferral of {@code kind}:
   * prepaid → {@code Dr PREPAID_EXPENSE / Cr CASH_CLEARING}; deferred revenue → {@code Dr
   * CASH_CLEARING / Cr DEFERRED_REVENUE}. Public + pure for the posting unit tests.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildCreationEntry(
      DeferralKind kind,
      String period,
      Instant now,
      UUID entryId,
      UUID sourceEventId,
      Money total) {
    String clearingCode = requireMapped(AccountRole.CASH_CLEARING, now);
    List<JournalLine> lines;
    String description;
    boolean usesIllustrative;
    if (kind == DeferralKind.PREPAID_EXPENSE) {
      String prepaidCode = requireMapped(AccountRole.PREPAID_EXPENSE, now);
      lines =
          List.of(
              JournalLine.debit(entryId, 1, prepaidCode, total),
              JournalLine.credit(entryId, 2, clearingCode, total));
      description = "Prepaid expense created";
      // Derived from the provenance of the PREPAID_EXPENSE/CASH_CLEARING mappings actually
      // resolved above, rather than hardcoded.
      usesIllustrative =
          roleAccountResolver.anyIllustrative(
              now, AccountRole.PREPAID_EXPENSE, AccountRole.CASH_CLEARING);
    } else {
      String deferredCode = requireMapped(AccountRole.DEFERRED_REVENUE, now);
      lines =
          List.of(
              JournalLine.debit(entryId, 1, clearingCode, total),
              JournalLine.credit(entryId, 2, deferredCode, total));
      description = "Deferred revenue received";
      // Derived from the provenance of the CASH_CLEARING/DEFERRED_REVENUE mappings actually
      // resolved above, rather than hardcoded.
      usesIllustrative =
          roleAccountResolver.anyIllustrative(
              now, AccountRole.CASH_CLEARING, AccountRole.DEFERRED_REVENUE);
    }
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        description,
        total.currency().getCurrencyCode(),
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
    journalEntryRepository.saveAndFlush(entry);
    for (var line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

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
