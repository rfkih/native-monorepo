package id.co.nativeapp.finance.withinclose.service;

import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.finance.revenue.projection.TrialBalanceLineView;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.withinclose.domain.BaseCurrencyMismatchException;
import id.co.nativeapp.finance.withinclose.domain.MultiCurrencyTrialBalanceException;
import id.co.nativeapp.finance.withinclose.domain.UndeterminableBaseCurrencyException;
import id.co.nativeapp.finance.withinclose.domain.UnmappedLedgerAccountException;
import id.co.nativeapp.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a company's BALANCED trial balance for a period from the dimensional ledger (P3d SEAM 4a),
 * ready for a within-company close to publish — the read-side service ({@code *Reader}) of the
 * close.
 *
 * <p><strong>What it builds.</strong> It rolls the period's {@code ledger_posting} rows up into one
 * line per {@code (gl_account_code, posting_type)} (the {@code account_type} resolved from {@code
 * chart_of_account}), then appends a BALANCING retained-earnings EQUITY closing line equal to
 * {@code −(Σ REVENUE − Σ EXPENSE)} so the lines sum SIGNED-TO-ZERO in the company's functional
 * currency under the double-entry sign convention (DEBIT classes ASSET/EXPENSE positive; CREDIT
 * classes LIABILITY/EQUITY/REVENUE negative). The P&amp;L closes to equity — this is precisely what
 * makes a member trial balance reconcile and pass the SEAM-3b native-balance gate when the GROUP
 * later consolidates it.
 *
 * <p><strong>Why an equity line, not separate FX.</strong> finance's ledger holds REVENUE + EXPENSE
 * postings (the P&amp;L) but no standalone balance-sheet capital line; a raw P&amp;L does not
 * balance on its own (net profit ≠ 0). The closing entry moves the net profit into retained
 * earnings (EQUITY), the standard period-end close, so the published trial balance is genuinely
 * balanced double-entry rather than a bare P&amp;L the group would reject as unbalanced.
 *
 * <p>Single functional currency: a company's postings for a period are all in its base currency, so
 * this reader fails loudly if it ever sees more than one currency (multi-currency within one
 * company would need FX, which is out of scope here, mirroring {@code PnlReader}).
 *
 * <p><strong>Base currency is DERIVED from the ledger, never declared by the caller (CLAUDE.md —
 * the immutable base-currency rule).</strong> The company's base (functional) currency is set at
 * company creation and is immutable once transactions exist; this reader takes the SINGLE currency
 * of the period's postings as the authoritative base currency. A caller-supplied currency is only
 * an OPTIONAL cross-check that FAILS LOUD ({@link BaseCurrencyMismatchException}) on mismatch —
 * finance never lets the request override the books. An EMPTY period reveals no ledger currency, so
 * no authoritative currency exists (and no {@code TrialBalancePublished} is emitted anyway); the
 * close records the cross-check currency for audit if one was supplied, else fails loud ({@link
 * UndeterminableBaseCurrencyException}).
 */
@Service
public class TrialBalanceReader {

  /**
   * The retained-earnings EQUITY account the period P&amp;L closes into — the balancing closing
   * line. A reserved consolidation/closing code (not a posting-mapped {@code chart_of_account}
   * row), named so an auditor sees the P&amp;L→equity close explicitly.
   */
  static final String RETAINED_EARNINGS_ACCOUNT = "3000-RETAINED-EARNINGS";

  /** The posting kind stamped on the closing line. */
  static final String CLOSING_POSTING_TYPE = "RETAINED_EARNINGS_CLOSING";

  private final LedgerPostingRepository ledgerRepository;

  public TrialBalanceReader(LedgerPostingRepository ledgerRepository) {
    this.ledgerRepository = ledgerRepository;
  }

  /**
   * Gathers the bound company's balanced trial balance for {@code period}, DERIVING the base
   * currency from the ledger (CLAUDE.md — the immutable base-currency rule). Must run inside the
   * company's {@code @Transactional} + RLS scope so the ledger read is tenant-scoped (rule 5).
   *
   * @param period the accounting period {@code YYYY-MM}
   * @param declaredCurrency an OPTIONAL caller-supplied ISO-4217 currency used ONLY as a
   *     cross-check against the currency DERIVED from the ledger; a mismatch FAILS LOUD ({@link
   *     BaseCurrencyMismatchException}). May be {@code null} (the body field is optional). For an
   *     EMPTY period it is recorded for audit if supplied; if {@code null} too, the close fails
   *     loud ({@link UndeterminableBaseCurrencyException}).
   * @throws BaseCurrencyMismatchException if {@code declaredCurrency} disagrees with the ledger
   * @throws UndeterminableBaseCurrencyException if the period is empty and no currency was supplied
   * @throws UnmappedLedgerAccountException if a posting has no resolvable {@code account_type}
   */
  @Transactional(readOnly = true)
  public CompanyTrialBalance gather(String period, String declaredCurrency) {
    List<TrialBalanceLineView> rows = ledgerRepository.trialBalanceForPeriod(period);
    String declared = declaredCurrency == null ? null : declaredCurrency.strip();
    if (rows.isEmpty()) {
      // EMPTY period: the ledger reveals no currency, so there is nothing authoritative to derive
      // and no TrialBalancePublished is emitted (it carries no currency on the wire). Record the
      // cross-check currency for audit if one was supplied; otherwise fail loud rather than
      // fabricate
      // a base currency the company never had.
      if (declared == null) {
        throw new UndeterminableBaseCurrencyException(period);
      }
      return new CompanyTrialBalance(declared, false, List.of());
    }

    // DERIVE the base currency from the ledger: the single currency of the period's postings is the
    // authoritative base currency (immutable, set at company creation). The first row seeds it;
    // every
    // other row must match (the single-functional-currency invariant — multi-currency within one
    // company would need FX, out of scope here).
    String baseCurrency = requireSingleCurrency(rows, period);

    // OPTIONAL cross-check: a caller-supplied currency must AGREE with the derived one — the
    // request
    // never overrides the books. A mismatch is a fail-loud 4xx (the dashboard's idea of the base
    // currency disagrees with the ledger).
    if (declared != null && !declared.equals(baseCurrency)) {
      throw new BaseCurrencyMismatchException(period, declared, baseCurrency);
    }

    List<TrialBalanceLine> lines = new ArrayList<>(rows.size() + 1);
    boolean usesIllustrative = false;
    // The signed P&L residual under the double-entry sign convention: REVENUE credits subtract,
    // EXPENSE debits add. Its negation is the retained-earnings closing line that balances the
    // books.
    Money signedPnl = Money.ofMinor(0L, baseCurrency);

    for (TrialBalanceLineView row : rows) {
      String currency = row.getCurrency().strip();
      // Defence-in-depth (FIX 2): the LEFT JOIN surfaces a posting with no chart_of_account row as
      // a
      // NULL account_type. FAIL LOUD rather than let the INNER-JOIN behaviour silently drop the
      // posting (its money would vanish while the equity line still balanced to the understated
      // net).
      String accountTypeRaw = row.getAccountType();
      if (accountTypeRaw == null) {
        throw new UnmappedLedgerAccountException(period, row.getGlAccountCode());
      }
      AccountType accountType = AccountType.valueOf(accountTypeRaw.toUpperCase(Locale.ROOT));
      Money amount = Money.ofMinor(row.getAmountMinor(), currency);
      usesIllustrative = usesIllustrative || row.getUsesIllustrativeRules();
      signedPnl = applySign(signedPnl, accountType, amount);
      lines.add(
          new TrialBalanceLine(
              row.getGlAccountCode(),
              accountType.name(),
              row.getPostingType(),
              row.getAmountMinor(),
              currency,
              null,
              null));
    }

    // The BALANCING retained-earnings EQUITY closing line. The trial balance currently sums to
    // `signedPnl` (the net P&L); EQUITY is a CREDIT class (subtracts under the sign convention), so
    // a
    // closing EQUITY magnitude M contributes −M. To drive the total to zero we need signedPnl − M =
    // 0
    // -> M = signedPnl. A profit (signedPnl < 0, since revenue credits dominate) yields a negative
    // magnitude; we store the magnitude as published (the sign convention is applied by the reader,
    // exactly as for every other line — see GroupCloseWriter.applySignConvention), so the equity
    // magnitude is `signedPnl` itself (which makes the signed total signedPnl − signedPnl = 0).
    Money equityClosingMagnitude = signedPnl;
    if (!equityClosingMagnitude.isZero()) {
      lines.add(
          new TrialBalanceLine(
              RETAINED_EARNINGS_ACCOUNT,
              AccountType.EQUITY.name(),
              CLOSING_POSTING_TYPE,
              equityClosingMagnitude.amountMinor(),
              baseCurrency,
              null,
              null));
    }

    return new CompanyTrialBalance(baseCurrency, usesIllustrative, List.copyOf(lines));
  }

  /**
   * Derives the company's base currency from the period's postings: the SINGLE currency every row
   * is in (CLAUDE.md — the base currency is immutable and read from the books, never declared).
   * Fails loud if the period ever holds more than one currency (multi-currency within one company
   * needs FX, out of scope here, mirroring {@code PnlReader}). {@code rows} is non-empty here.
   */
  private static String requireSingleCurrency(List<TrialBalanceLineView> rows, String period) {
    String baseCurrency = rows.getFirst().getCurrency().strip();
    for (TrialBalanceLineView row : rows) {
      String currency = row.getCurrency().strip();
      if (!currency.equals(baseCurrency)) {
        // A TYPED fault (mapped to 422) rather than a bare IllegalStateException (a generic 500) —
        // an intra-company multi-currency period is malformed input the close cannot process, in
        // the
        // same fail-loud class as the other within-close faults (#42).
        throw new MultiCurrencyTrialBalanceException(period, baseCurrency, currency);
      }
    }
    return baseCurrency;
  }

  /**
   * The double-entry sign convention: DEBIT-normal classes (ASSET, EXPENSE) add their magnitude;
   * CREDIT-normal classes (LIABILITY, EQUITY, REVENUE) subtract. Identical to {@code
   * GroupCloseWriter.applySignConvention}, so the trial balance the close publishes reconciles to
   * zero under the very same convention the group consolidation re-checks it with.
   */
  private static Money applySign(Money running, AccountType type, Money magnitude) {
    return switch (type) {
      case ASSET, EXPENSE -> running.plus(magnitude);
      case LIABILITY, EQUITY, REVENUE -> running.minus(magnitude);
    };
  }
}
