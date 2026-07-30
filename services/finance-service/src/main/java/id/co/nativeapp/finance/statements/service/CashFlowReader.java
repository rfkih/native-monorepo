package id.co.nativeapp.finance.statements.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.GlUnmappedAccountException;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.finance.statements.dto.CashFlowLineItem;
import id.co.nativeapp.finance.statements.dto.CashFlowResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Cash Flow Statement (Arus Kas) for a period from the double-entry GL — the third core
 * statement, alongside {@link IncomeStatementReader} + {@link BalanceSheetReader}, all GL-derived
 * (no new tables). Uses the <strong>indirect method</strong> (ADR 0019).
 *
 * <p><strong>Method.</strong> {@code glTrialBalance(period)} already IS the per-account net
 * movement for the period; for each line, movement {@code m = debit − credit}. The <em>cash &amp;
 * equivalents</em> accounts (resolved from the {@code BANK}/{@code CASH_CLEARING}/{@code
 * QRIS_CLEARING}/{@code CARD_CLEARING} roles via {@link RoleAccountResolver} — SME-pluggable, not
 * hard-coded) are set aside; their Σ{@code m} is the actual net cash movement. Every other account
 * contributes to the change in cash in <em>cash-impact</em> terms:
 *
 * <ul>
 *   <li>REVENUE / EXPENSE → net income (the operating starting point): revenue net = credit−debit,
 *       expense net = debit−credit.
 *   <li>Non-cash ASSET / LIABILITY / EQUITY → a working-capital / financing adjustment {@code =
 *       credit − debit} (an asset increase uses cash; a liability increase provides cash),
 *       classified operating (current AR/AP/VAT — everything today) / investing (non-current assets
 *       — none yet, reserved for Phase 6 fixed assets) / financing (equity, long-term debt — none
 *       seeded).
 * </ul>
 *
 * <p><strong>Disposal reclassification (ADR 0022).</strong> The trial balance aggregates
 * acquisitions, depreciation, and disposals into the same 1500/1590 rows, so a disposal period is
 * reclassified from the FROZEN per-asset disposal columns ({@code fixed_asset} where {@code
 * disposal_period = period} — exactly what the disposal entries posted, RLS-scoped): the 1500 line
 * drops the disposal credits (pure capex), a single {@code DISPOSAL_PROCEEDS} investing inflow is
 * emitted, the 1590 line drops the disposal debits (pure depreciation add-back), and the P&amp;L
 * gain/loss is backed out of operating as GROSS reclass lines on the gain/loss account codes. The
 * net of all five adjustments is identically zero for ANY aggregate values (the gain definition
 * telescopes), so the reconciliation below still closes — note it therefore does NOT cross-check
 * the frozen columns against the posted legs; that consistency is owned by the disposal writer,
 * which freezes the same amounts it posts, under the shared asset-posting lock.
 *
 * <p><strong>Exact reconciliation.</strong> Because {@link GlTrialBalanceReader#read} asserts
 * Σdebit==Σcredit (so Σ{@code m} over ALL accounts is zero), the derived {@code netChangeInCash}
 * equals the actual cash-account movement <em>identically</em>. The reader asserts this — a
 * mismatch means an account was left unclassified (an internal bug), surfaced as a 500 exactly like
 * {@link BalanceSheetReader}'s balance check.
 *
 * <p>Must be called inside a tenant-bound {@code @Transactional} scope so RLS applies (rule 5).
 */
@Service
public class CashFlowReader {

  /**
   * The roles whose accounts are "cash &amp; cash equivalents" for the statement (SME-pluggable).
   */
  private static final AccountRole[] CASH_ROLES = {
    AccountRole.BANK,
    AccountRole.CASH_CLEARING,
    AccountRole.QRIS_CLEARING,
    AccountRole.CARD_CLEARING
  };

  /**
   * The roles whose accounts classify as INVESTING (Phase 6, ADR 0020): the non-current fixed-asset
   * COST account — its movement is capex. Deliberately NOT {@code ACCUMULATED_DEPRECIATION} (also
   * ASSET-typed): that stays in OPERATING as the classic non-cash depreciation add-back that
   * offsets the expense's hit to net income. Role-resolved like {@link #CASH_ROLES}
   * (SME-pluggable), because both 1500 and 1590 are ASSET-typed — the split cannot come from {@code
   * AccountType}.
   */
  private static final AccountRole[] INVESTING_ROLES = {AccountRole.FIXED_ASSET_COST};

  /** The cash-flow activity a non-cash balance-sheet account's movement belongs to. */
  private enum Activity {
    OPERATING,
    INVESTING,
    FINANCING
  }

  /**
   * The synthetic {@code accountCode} of the single investing "proceeds from asset disposal" inflow
   * line (ADR 0022) — not a chart account; the console maps it to a localized label.
   */
  public static final String DISPOSAL_PROCEEDS_CODE = "DISPOSAL_PROCEEDS";

  /** Per-period disposal aggregates, read from the frozen {@code fixed_asset} disposal columns. */
  private record DisposalAggregates(
      long costMinor,
      long accumulatedMinor,
      long proceedsMinor,
      long grossGainMinor,
      long grossLossMinor) {
    boolean any() {
      return costMinor != 0
          || accumulatedMinor != 0
          || proceedsMinor != 0
          || grossGainMinor != 0
          || grossLossMinor != 0;
    }
  }

  private final GlTrialBalanceReader glTrialBalanceReader;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public CashFlowReader(
      GlTrialBalanceReader glTrialBalanceReader,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.glTrialBalanceReader =
        Objects.requireNonNull(glTrialBalanceReader, "glTrialBalanceReader");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Builds the Cash Flow Statement for {@code period}, or {@link Optional#empty()} when the period
   * has no GL entries (→ 204).
   *
   * @throws id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException if the GL is multi-currency
   *     (→ 422, from the reader)
   * @throws GlUnmappedAccountException defence-in-depth if a line has no {@code account_type}
   * @throws IllegalStateException if the GL is unbalanced, or the derived net change in cash does
   *     not reconcile to the cash-account movement (internal invariants; 500)
   */
  @Transactional(readOnly = true)
  public Optional<CashFlowResponse> read(String period) {
    Objects.requireNonNull(period, "period");
    List<GlTrialBalanceLineView> lines = glTrialBalanceReader.read(period);
    if (lines.isEmpty()) {
      return Optional.empty();
    }

    String currency = lines.getFirst().getCurrency().strip();
    Set<String> cashCodes = resolveCashCodes();
    Set<String> investingCodes = resolveInvestingCodes();

    long totalRevenue = 0L;
    long totalExpense = 0L;
    long cashMovement = 0L;
    long operatingAdj = 0L;
    long investingAdj = 0L;
    long financingAdj = 0L;
    List<CashFlowLineItem> operatingLines = new ArrayList<>();
    List<CashFlowLineItem> investingLines = new ArrayList<>();
    List<CashFlowLineItem> financingLines = new ArrayList<>();
    boolean usesIllustrative = false;

    for (GlTrialBalanceLineView line : lines) {
      String accountTypeRaw = line.getAccountType();
      if (accountTypeRaw == null) {
        throw new GlUnmappedAccountException("period " + period, line.getAccountCode());
      }
      usesIllustrative = usesIllustrative || line.getUsesIllustrativeRules();

      long movement =
          Math.subtractExact(
              line.getTotalDebitMinor(), line.getTotalCreditMinor()); // debit − credit

      if (cashCodes.contains(line.getAccountCode())) {
        cashMovement = Math.addExact(cashMovement, movement);
        continue;
      }

      AccountType accountType = AccountType.valueOf(accountTypeRaw.toUpperCase(Locale.ROOT));
      switch (accountType) {
        case REVENUE ->
            totalRevenue =
                Math.addExact(
                    totalRevenue,
                    Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor()));
        case EXPENSE ->
            totalExpense =
                Math.addExact(
                    totalExpense,
                    Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor()));
        // ASSET, LIABILITY, EQUITY — the only remaining AccountType values.
        default -> {
          // Cash-impact of the movement = credit − debit = −(debit − credit): an asset increase
          // uses cash, a liability/equity increase provides it.
          long adjustment = Math.negateExact(movement);
          CashFlowLineItem item =
              new CashFlowLineItem(line.getAccountCode(), accountType.name(), adjustment);
          switch (classify(line.getAccountCode(), accountType, investingCodes)) {
            case OPERATING -> {
              operatingLines.add(item);
              operatingAdj = Math.addExact(operatingAdj, adjustment);
            }
            case INVESTING -> {
              investingLines.add(item);
              investingAdj = Math.addExact(investingAdj, adjustment);
            }
            // FINANCING — the only remaining Activity value.
            default -> {
              financingLines.add(item);
              financingAdj = Math.addExact(financingAdj, adjustment);
            }
          }
        }
      }
    }

    // Disposal reclassification (ADR 0022) — see the class doc. Every delta below nets to zero
    // (Σaccum − Σgain − Σcost + Σproceeds ≡ 0 by the gain definition), preserving reconciliation.
    DisposalAggregates disposals = readDisposalAggregates(period);
    if (disposals.any()) {
      Instant asOf = clock.instant();
      // Investing: strip the disposal credits off the cost line (pure capex remains)…
      String costCode = requireRole(AccountRole.FIXED_ASSET_COST, asOf);
      adjustLine(investingLines, costCode, AccountType.ASSET.name(), -disposals.costMinor());
      investingAdj = Math.subtractExact(investingAdj, disposals.costMinor());
      // …and show the sale as ONE cash inflow line.
      if (disposals.proceedsMinor() != 0) {
        investingLines.add(
            new CashFlowLineItem(
                DISPOSAL_PROCEEDS_CODE, AccountType.ASSET.name(), disposals.proceedsMinor()));
        investingAdj = Math.addExact(investingAdj, disposals.proceedsMinor());
      }
      // Operating: strip the disposal debits off 1590 (the pure depreciation add-back remains)…
      if (disposals.accumulatedMinor() != 0) {
        String accumCode = requireRole(AccountRole.ACCUMULATED_DEPRECIATION, asOf);
        adjustLine(
            operatingLines, accumCode, AccountType.ASSET.name(), disposals.accumulatedMinor());
        operatingAdj = Math.addExact(operatingAdj, disposals.accumulatedMinor());
      }
      // …and back the P&L gain/loss out of operating, GROSS (matching the income statement's
      // lines).
      if (disposals.grossGainMinor() != 0) {
        operatingLines.add(
            new CashFlowLineItem(
                requireRole(AccountRole.GAIN_ON_DISPOSAL, asOf),
                AccountType.REVENUE.name(),
                Math.negateExact(disposals.grossGainMinor())));
        operatingAdj = Math.subtractExact(operatingAdj, disposals.grossGainMinor());
      }
      if (disposals.grossLossMinor() != 0) {
        operatingLines.add(
            new CashFlowLineItem(
                requireRole(AccountRole.LOSS_ON_DISPOSAL, asOf),
                AccountType.EXPENSE.name(),
                disposals.grossLossMinor()));
        operatingAdj = Math.addExact(operatingAdj, disposals.grossLossMinor());
      }
    }

    long netIncome = Math.subtractExact(totalRevenue, totalExpense);
    long cashFromOperating = Math.addExact(netIncome, operatingAdj);
    long cashFromInvesting = investingAdj;
    long cashFromFinancing = financingAdj;
    long netChangeInCash =
        Math.addExact(Math.addExact(cashFromOperating, cashFromInvesting), cashFromFinancing);

    if (netChangeInCash != cashMovement) {
      throw new IllegalStateException(
          "Cash flow for period "
              + period
              + " does not reconcile: derived net change in cash="
              + netChangeInCash
              + " actual cash-account movement="
              + cashMovement
              + " — an account was left unclassified (bug in CashFlowReader) or the GL is unbalanced");
    }

    return Optional.of(
        new CashFlowResponse(
            period,
            currency,
            netIncome,
            List.copyOf(operatingLines),
            cashFromOperating,
            List.copyOf(investingLines),
            cashFromInvesting,
            List.copyOf(financingLines),
            cashFromFinancing,
            netChangeInCash,
            cashMovement,
            true,
            usesIllustrative));
  }

  /**
   * Classifies a non-cash balance-sheet account's movement into a cash-flow activity. ILLUSTRATIVE
   * (ADR 0019/0020): the role-resolved investing set wins (fixed-asset cost → capex); then EQUITY →
   * financing; everything else (current working capital + the accumulated-depreciation add-back +
   * prepaid/deferred-revenue) is operating. The finer current-vs-non-current / long-term-debt split
   * is SME-gated.
   */
  private static Activity classify(
      String accountCode, AccountType accountType, Set<String> investingCodes) {
    if (investingCodes.contains(accountCode)) {
      return Activity.INVESTING;
    }
    return accountType == AccountType.EQUITY ? Activity.FINANCING : Activity.OPERATING;
  }

  /**
   * The frozen disposal aggregates for the period (RLS applies on the {@code @Transactional}
   * connection): Σcost / Σaccumulated / Σproceeds derecognized by disposal entries whose POSTING
   * period is {@code period}, plus the GROSS gain and loss sums (per-asset {@code gain = proceeds +
   * accumulated − cost}, split by sign so mixed gain/loss periods reclass line-for-line against the
   * income statement).
   */
  private DisposalAggregates readDisposalAggregates(String period) {
    return jdbcTemplate.queryForObject(
        """
        SELECT COALESCE(SUM(cost_minor), 0),
               COALESCE(SUM(accumulated_disposed_minor), 0),
               COALESCE(SUM(proceeds_minor), 0),
               COALESCE(SUM(GREATEST(proceeds_minor + accumulated_disposed_minor - cost_minor, 0)), 0),
               COALESCE(SUM(GREATEST(cost_minor - proceeds_minor - accumulated_disposed_minor, 0)), 0)
          FROM fixed_asset
         WHERE status = 'DISPOSED' AND disposal_period = ?
        """,
        (rs, rowNum) ->
            new DisposalAggregates(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
        period);
  }

  /**
   * Adds {@code deltaMinor} to the line with {@code accountCode} (removing it when it nets to
   * zero), or appends a new line when absent — keeps the bucket's line list consistent with its
   * adjusted sum.
   */
  private static void adjustLine(
      List<CashFlowLineItem> bucket, String accountCode, String accountType, long deltaMinor) {
    if (deltaMinor == 0) {
      return;
    }
    for (int i = 0; i < bucket.size(); i++) {
      CashFlowLineItem item = bucket.get(i);
      if (item.accountCode().equals(accountCode)) {
        long adjusted = Math.addExact(item.amountMinor(), deltaMinor);
        if (adjusted == 0) {
          bucket.remove(i);
        } else {
          bucket.set(i, new CashFlowLineItem(accountCode, item.accountType(), adjusted));
        }
        return;
      }
    }
    bucket.add(new CashFlowLineItem(accountCode, accountType, deltaMinor));
  }

  /** Resolves a role's account code, failing loud when a disposal period needs an unmapped role. */
  private String requireRole(AccountRole role, Instant asOf) {
    String code = roleAccountResolver.resolve(role, asOf);
    if (code == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for "
              + role
              + " — required to reclassify a disposal period");
    }
    return code;
  }

  /**
   * Resolves the cash &amp; cash-equivalent account codes from the cash roles (skips any unmapped).
   */
  private Set<String> resolveCashCodes() {
    return resolveCodes(CASH_ROLES);
  }

  /** Resolves the investing account codes (fixed-asset cost) from the investing roles. */
  private Set<String> resolveInvestingCodes() {
    return resolveCodes(INVESTING_ROLES);
  }

  private Set<String> resolveCodes(AccountRole[] roles) {
    Instant asOf = clock.instant();
    Set<String> codes = new HashSet<>();
    for (AccountRole role : roles) {
      String code = roleAccountResolver.resolve(role, asOf);
      if (code != null) {
        codes.add(code);
      }
    }
    return codes;
  }
}
