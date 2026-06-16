package id.co.nativeapp.finance.pnl.domain;

import java.io.Serial;

/**
 * Thrown when a {@code SaleRecorded} / {@code ExpenseRecorded} posting arrives in a currency that
 * DIFFERS from the one already established for the company's period in {@code consolidated_pnl}
 * (#26 — finance-expansion robustness).
 *
 * <p>A company has ONE base (functional) currency, set at company creation and immutable once
 * transactions exist (CLAUDE.md). Every revenue/expense posting for that company is therefore in
 * that single currency. A posting whose currency diverges is a producer-side contract violation (a
 * vertical emitted the wrong currency) — there is no FX in the core revenue/expense path. Left
 * unchecked it would silently create a SECOND {@code consolidated_pnl} (and {@code
 * consolidated_revenue}) row keyed on {@code (company, period, currency)}, which then detonates at
 * READ time as a generic {@code 500} ({@code PnlReader} rejects a multi-currency period) — the bad
 * money sitting in a divergent row and poisoning every P&amp;L read for the period.
 *
 * <p>Instead the write-time guard FAILS CLOSED: this typed, deterministic (non-retryable) fault
 * rolls the consume transaction back so NO divergent row is ever created, and the record is routed
 * to {@code <topic>.DLT} — the money is held durably for an operator to investigate (HR-3: never
 * silently dropped), and the period's P&amp;L read stays clean for the legitimate base currency.
 * Mirrors the labor path's currency-divergence guard ({@code LaborCostPostingWriter}'s {@code
 * CURRENCY_MISMATCH} terminal state) and the within-company close's {@code
 * MultiCurrencyTrialBalanceException}.
 */
public final class MismatchedPostingCurrencyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param period the accounting period the posting falls in
   * @param establishedCurrency the ISO-4217 currency already established for the period
   * @param postingCurrency the divergent ISO-4217 currency the incoming posting carried
   */
  public MismatchedPostingCurrencyException(
      String period, String establishedCurrency, String postingCurrency) {
    super(
        "Posting currency "
            + postingCurrency
            + " for period "
            + period
            + " differs from the period's established currency "
            + establishedCurrency
            + "; a company has one immutable base currency and there is no FX in the revenue/expense"
            + " path, so this posting is rejected (fail-closed to DLT) rather than creating a"
            + " divergent second-currency P&L row");
  }
}
