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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Cash Flow Statement (Arus Kas) for a period from the double-entry GL — the third core
 * statement, alongside {@link IncomeStatementReader} + {@link BalanceSheetReader}, all GL-derived (no
 * new tables). Uses the <strong>indirect method</strong> (ADR 0019).
 *
 * <p><strong>Method.</strong> {@code glTrialBalance(period)} already IS the per-account net movement
 * for the period; for each line, movement {@code m = debit − credit}. The <em>cash &amp;
 * equivalents</em> accounts (resolved from the {@code BANK}/{@code CASH_CLEARING}/{@code
 * QRIS_CLEARING}/{@code CARD_CLEARING} roles via {@link RoleAccountResolver} — SME-pluggable, not
 * hard-coded) are set aside; their Σ{@code m} is the actual net cash movement. Every other account
 * contributes to the change in cash in <em>cash-impact</em> terms:
 *
 * <ul>
 *   <li>REVENUE / EXPENSE → net income (the operating starting point): revenue net = credit−debit,
 *       expense net = debit−credit.
 *   <li>Non-cash ASSET / LIABILITY / EQUITY → a working-capital / financing adjustment {@code =
 *       credit − debit} (an asset increase uses cash; a liability increase provides cash), classified
 *       operating (current AR/AP/VAT — everything today) / investing (non-current assets — none yet,
 *       reserved for Phase 6 fixed assets) / financing (equity, long-term debt — none seeded).
 * </ul>
 *
 * <p><strong>Exact reconciliation.</strong> Because {@link GlTrialBalanceReader#read} asserts
 * Σdebit==Σcredit (so Σ{@code m} over ALL accounts is zero), the derived {@code netChangeInCash}
 * equals the actual cash-account movement <em>identically</em>. The reader asserts this — a mismatch
 * means an account was left unclassified (an internal bug), surfaced as a 500 exactly like {@link
 * BalanceSheetReader}'s balance check.
 *
 * <p>Must be called inside a tenant-bound {@code @Transactional} scope so RLS applies (rule 5).
 */
@Service
public class CashFlowReader {

  /** The roles whose accounts are "cash &amp; cash equivalents" for the statement (SME-pluggable). */
  private static final AccountRole[] CASH_ROLES = {
    AccountRole.BANK,
    AccountRole.CASH_CLEARING,
    AccountRole.QRIS_CLEARING,
    AccountRole.CARD_CLEARING
  };

  /** The cash-flow activity a non-cash balance-sheet account's movement belongs to. */
  private enum Activity {
    OPERATING,
    INVESTING,
    FINANCING
  }

  private final GlTrialBalanceReader glTrialBalanceReader;
  private final RoleAccountResolver roleAccountResolver;
  private final Clock clock;

  public CashFlowReader(
      GlTrialBalanceReader glTrialBalanceReader,
      RoleAccountResolver roleAccountResolver,
      Clock clock) {
    this.glTrialBalanceReader =
        Objects.requireNonNull(glTrialBalanceReader, "glTrialBalanceReader");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Builds the Cash Flow Statement for {@code period}, or {@link Optional#empty()} when the period
   * has no GL entries (→ 204).
   *
   * @throws id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException if the GL is multi-currency
   *     (→ 422, from the reader)
   * @throws GlUnmappedAccountException defence-in-depth if a line has no {@code account_type}
   * @throws IllegalStateException if the GL is unbalanced, or the derived net change in cash does not
   *     reconcile to the cash-account movement (internal invariants; 500)
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
          Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor()); // debit − credit

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
        case ASSET, LIABILITY, EQUITY -> {
          // Cash-impact of the movement = credit − debit = −(debit − credit): an asset increase uses
          // cash, a liability/equity increase provides it.
          long adjustment = Math.negateExact(movement);
          CashFlowLineItem item =
              new CashFlowLineItem(line.getAccountCode(), accountType.name(), adjustment);
          switch (classify(accountType)) {
            case OPERATING -> {
              operatingLines.add(item);
              operatingAdj = Math.addExact(operatingAdj, adjustment);
            }
            case INVESTING -> {
              investingLines.add(item);
              investingAdj = Math.addExact(investingAdj, adjustment);
            }
            case FINANCING -> {
              financingLines.add(item);
              financingAdj = Math.addExact(financingAdj, adjustment);
            }
          }
        }
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
   * (ADR 0019): everything current is operating; EQUITY is financing. The current-vs-non-current
   * split (non-current assets → investing, long-term debt → financing) is SME-gated and reserved for
   * Phase 6 fixed assets — today the COA has only current assets/liabilities + synthetic retained
   * earnings, so operating captures all working capital.
   */
  private static Activity classify(AccountType accountType) {
    return accountType == AccountType.EQUITY ? Activity.FINANCING : Activity.OPERATING;
  }

  /** Resolves the cash &amp; cash-equivalent account codes from the cash roles (skips any unmapped). */
  private Set<String> resolveCashCodes() {
    Instant asOf = clock.instant();
    Set<String> codes = new HashSet<>();
    for (AccountRole role : CASH_ROLES) {
      String code = roleAccountResolver.resolve(role, asOf);
      if (code != null) {
        codes.add(code);
      }
    }
    return codes;
  }
}
