package id.co.nativeapp.finance.pnl.domain;

import id.co.nativeapp.money.Money;
import java.util.Objects;

/**
 * The dashboard P&amp;L figures for one period — the read-path carrier the {@code GET /api/v1/pnl}
 * query returns to the controller (#21). It holds the period's revenue and expense as the rule-8
 * {@link Money} pair (minor units + ISO-4217), with the net (revenue − expense) DERIVED on read so
 * it can never drift from its legs.
 *
 * <p>Unlike {@link ConsolidatedPnl} (the incrementally-accumulated write-model entity), this is a
 * pure immutable value with no persistence. Since ADR 0065 the dashboard P&amp;L is DERIVED from
 * the GL trial balance via the same computation that backs the income statement ({@code
 * IncomeStatementReader}), so the beranda "Laba bersih" and the Laba-Rugi report are equal by
 * construction — the {@code consolidated_pnl} accumulator (which fed the dashboard before) missed
 * every GL posting that never ran through its writers (register-close cash variance, QRIS/platform
 * fees, depreciation, AR/AP, service charge…), causing the two screens to diverge.
 *
 * <p>Mirrors the {@code revenue()} / {@code expense()} / {@code net()} / {@code
 * usesIllustrativeRules()} surface of {@link ConsolidatedPnl} so the DTO mapping ({@code
 * PnlResponse.from/withPresentation}) and the FX presentation converter accept either shape with no
 * behavioural change.
 *
 * @param period the accounting period {@code YYYY-MM}
 * @param revenue the period's total REVENUE as {@link Money}
 * @param expense the period's total EXPENSE as {@link Money}
 * @param usesIllustrativeRules whether any GL entry in the period used illustrative-placeholder
 *     rules (#23) — the dashboard keys a provisional badge off it (rule 9)
 */
public record PnlFigures(
    String period, Money revenue, Money expense, boolean usesIllustrativeRules) {

  public PnlFigures {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(revenue, "revenue");
    Objects.requireNonNull(expense, "expense");
  }

  /**
   * The P&amp;L net (revenue − expense) as {@link Money}; {@code Money.minus} refuses mixed
   * currency.
   */
  public Money net() {
    return revenue.minus(expense);
  }
}
