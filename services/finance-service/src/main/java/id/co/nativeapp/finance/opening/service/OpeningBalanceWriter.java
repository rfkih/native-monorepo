package id.co.nativeapp.finance.opening.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.opening.domain.CompanyOpeningBalance;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceAlreadyRecordedException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceIdempotencyKeyConflictException;
import id.co.nativeapp.finance.opening.domain.OpeningBalancePnlAccountException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceSealedPeriodException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceSide;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceLine;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceResult;
import id.co.nativeapp.finance.opening.repository.CompanyOpeningBalanceRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for recording a company's OPENING BALANCE SHEET (ADR
 * 0037) — the ad-hoc balanced-entry idiom (the disposal / tax / platform-settlement pattern, no
 * Kafka event) with an auto-plug to Opening Balance Equity so the entry always balances:
 *
 * <pre>
 * Dr each asset / Cr each liability + equity / (± Cr|Dr OPENING_BALANCE_EQUITY for the residual)
 * </pre>
 *
 * <p>Guards: a REQUIRED {@code Idempotency-Key} (replay-by-key-first, payload-verification 409), a
 * once-only-per-company marker (a different key → 409 already-recorded), balance-sheet accounts
 * only (a REVENUE/EXPENSE line → 422), an unsealed period (422), and a consistent posting currency
 * (422). A per-company advisory lock serializes concurrent first-time posts so the loser replays
 * rather than dying on the {@code UNIQUE(company_id)} backstop.
 */
@Component
public class OpeningBalanceWriter {

  private final CompanyOpeningBalanceRepository repository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;

  public OpeningBalanceWriter(
      CompanyOpeningBalanceRepository repository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Records the opening balance sheet as of {@code asOfDate}, in {@code currency}, from the
   * balance-sheet {@code lines}. Auto-plugs the residual to Opening Balance Equity.
   *
   * @throws OpeningBalanceIdempotencyKeyConflictException replayed key, different payload (409)
   * @throws OpeningBalanceAlreadyRecordedException already recorded under a different key (409)
   * @throws OpeningBalancePnlAccountException a line targets a REVENUE/EXPENSE account (422)
   * @throws OpeningBalanceSealedPeriodException the period is tax-sealed (422)
   * @throws MismatchedPostingCurrencyException the period already has a divergent GL currency (422)
   */
  @Transactional
  public OpeningBalanceResult record(
      LocalDate asOfDate, String currency, List<OpeningBalanceLine> lines, String idempotencyKey) {
    Objects.requireNonNull(asOfDate, "asOfDate");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(lines, "lines");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    currency = currency.strip().toUpperCase(Locale.ROOT);

    // The illustrative COA + role maps are effective from 2000-01-01; a go-live date before that
    // cannot resolve the equity/plug roles (a pre-2000 opening balance sheet is absurd anyway) —
    // reject up front as a clean 400 rather than a downstream 500 (review L1).
    if (asOfDate.getYear() < 2000) {
      throw new IllegalArgumentException("asOfDate must not be before the year 2000: " + asOfDate);
    }

    // The debit/credit split of the final balanced entry (cheap, no DB): totalDebit = totalCredit;
    // the signed plug credited to OBE is (Σuserdebit − Σusercredit).
    long userDebit = sumSide(lines, OpeningBalanceSide.DEBIT);
    long userCredit = sumSide(lines, OpeningBalanceSide.CREDIT);
    long total = Math.max(userDebit, userCredit);
    long plug = Math.subtractExact(userDebit, userCredit);
    String fingerprint = payloadFingerprint(asOfDate, currency, lines);

    String companyId = TenantContext.require().companyId();

    // Serialize concurrent first-time posts for this tenant (the PlatformSettlementWriter idiom):
    // the
    // loser blocks until the winner COMMITS, then the re-probe below replays instead of colliding
    // on
    // uq_company_opening_balance_company as an opaque 500.
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "opening_balance:" + companyId);

    // Replay-by-key probe FIRST: a genuine retry never re-posts.
    Optional<CompanyOpeningBalance> byKey = repository.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      CompanyOpeningBalance existing = byKey.get();
      boolean samePayload = fingerprint.equals(existing.getPayloadFingerprint());
      if (!samePayload) {
        throw new OpeningBalanceIdempotencyKeyConflictException(
            "Idempotency-Key was already used for a different opening balance sheet");
      }
      return new OpeningBalanceResult(existing, false);
    }

    // Once-only: any row under a DIFFERENT key means opening balances were already recorded.
    if (repository.count() > 0) {
      throw new OpeningBalanceAlreadyRecordedException(
          "opening balances have already been recorded for this company — they are once-only and"
              + " cannot be amended");
    }

    // The opening entry posts into the AS-OF month (a real YYYY-MM — never a sentinel; statements
    // filter period<=asOf lexicographically). occurred_at is the as-of instant so periodOf agrees.
    Instant occurredAt = asOfDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    String period = LedgerPosting.periodOf(occurredAt);
    requireNotSealed(period);
    requireConsistentGlCurrency(period, Money.ofMinor(total, currency));
    requireBalanceSheetAccounts(lines);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildOpeningEntry(lines, currency, plug, period, occurredAt, entryId);
    persistEntry(entry, companyId);

    CompanyOpeningBalance marker =
        new CompanyOpeningBalance(
            entryId, asOfDate, period, total, plug, currency, fingerprint, idempotencyKey);
    marker.setCompanyId(companyId);
    repository.save(marker);

    return new OpeningBalanceResult(marker, true);
  }

  /** The recorded opening balance for this company, if any (dashboard read; RLS-scoped, ≤1 row). */
  @Transactional(readOnly = true)
  public Optional<CompanyOpeningBalance> current() {
    return repository.findAll().stream().findFirst();
  }

  /**
   * Builds (but does not persist) the balanced opening entry — public + pure (no DB beyond the OBE
   * role lookup) so a unit test can assert the exact legs, mirroring {@code
   * PlatformSettlementWriter#buildSettlementEntry}. The plug line (Cr OBE when {@code plug > 0}, Dr
   * OBE when {@code plug < 0}) is omitted when the user's sheet already balances. {@code
   * source_event_id = entryId} (its own id, the UNIQUE backstop).
   */
  public JournalEntry buildOpeningEntry(
      List<OpeningBalanceLine> lines,
      String currency,
      long plug,
      String period,
      Instant occurredAt,
      UUID entryId) {
    java.util.List<JournalLine> journalLines = new java.util.ArrayList<>();
    int lineNo = 1;
    for (OpeningBalanceLine line : lines) {
      Money amount = Money.ofMinor(line.amountMinor(), currency);
      if (line.side() == OpeningBalanceSide.DEBIT) {
        journalLines.add(JournalLine.debit(entryId, lineNo++, line.accountCode(), amount));
      } else {
        journalLines.add(JournalLine.credit(entryId, lineNo++, line.accountCode(), amount));
      }
    }
    if (plug != 0) {
      String obe = requireMapped(AccountRole.OPENING_BALANCE_EQUITY, occurredAt);
      Money plugAmount = Money.ofMinor(Math.abs(plug), currency);
      if (plug > 0) {
        journalLines.add(JournalLine.credit(entryId, lineNo, obe, plugAmount));
      } else {
        journalLines.add(JournalLine.debit(entryId, lineNo, obe, plugAmount));
      }
    }
    return JournalEntry.balanced(
        entryId, period, occurredAt, "Opening balances", currency, entryId, true, journalLines);
  }

  private static long sumSide(List<OpeningBalanceLine> lines, OpeningBalanceSide side) {
    long sum = 0L;
    for (OpeningBalanceLine line : lines) {
      if (line.side() == side) {
        sum = Math.addExact(sum, line.amountMinor());
      }
    }
    return sum;
  }

  /**
   * A stable SHA-256 (hex) fingerprint of the opening payload — as-of + currency + the lines sorted
   * by {@code accountCode:side:amount} so it is order-independent. The replay conflict check
   * compares this, so two different line-sets that happen to share the same total do NOT masquerade
   * as the same payload (review M1).
   */
  private static String payloadFingerprint(
      LocalDate asOfDate, String currency, List<OpeningBalanceLine> lines) {
    String canonical =
        lines.stream()
            .map(l -> l.accountCode() + ":" + l.side() + ":" + l.amountMinor())
            .sorted()
            .collect(Collectors.joining(","));
    byte[] bytes = (asOfDate + "|" + currency + "|" + canonical).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * Every distinct line account must exist and be a balance-sheet type (ASSET/LIABILITY/EQUITY).
   */
  private void requireBalanceSheetAccounts(List<OpeningBalanceLine> lines) {
    for (String code :
        new LinkedHashSet<>(lines.stream().map(OpeningBalanceLine::accountCode).toList())) {
      List<String> types =
          jdbcTemplate.query(
              "SELECT account_type FROM chart_of_account WHERE account_code = ?",
              (rs, rowNum) -> rs.getString(1),
              code);
      if (types.isEmpty()) {
        throw new IllegalArgumentException("unknown account code: " + code);
      }
      String type = types.getFirst();
      if ("REVENUE".equals(type) || "EXPENSE".equals(type)) {
        throw new OpeningBalancePnlAccountException(code, type);
      }
    }
  }

  private void requireNotSealed(String period) {
    Boolean sealed =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tax_filing WHERE period = ?)", Boolean.class, period);
    if (Boolean.TRUE.equals(sealed)) {
      throw new OpeningBalanceSealedPeriodException(
          "period "
              + period
              + " is sealed (a tax filing exists) — opening balances cannot post"
              + " into it");
    }
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
    for (JournalLine line : entry.getLines()) {
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
