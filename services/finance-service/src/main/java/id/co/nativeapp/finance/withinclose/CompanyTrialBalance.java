package id.co.nativeapp.finance.withinclose;

import id.co.nativeapp.finance.grouptb.TrialBalancePublishedEvent.TrialBalanceLine;
import java.util.List;

/**
 * A company's BALANCED trial balance for a period (P3d SEAM 4a), as gathered by {@link
 * TrialBalanceGatherer}: the REVENUE + EXPENSE lines from the dimensional ledger PLUS the balancing
 * retained-earnings EQUITY closing line, so the lines sum signed-to-zero in the company's
 * functional currency (the P&amp;L closes to equity — exactly what makes a member trial balance
 * pass the SEAM-3b native-balance gate).
 *
 * <p>{@code lines} reuses the {@link TrialBalanceLine} carrier the {@code TrialBalancePublished}
 * producer + consumer share, so the gatherer's output drops straight onto the wire. {@code
 * baseCurrency} is the single functional currency every line is in — DERIVED from the ledger (the
 * immutable base currency, not declared by the caller); {@code usesIllustrativeRules} is the
 * sticky-OR across the period's postings. For an EMPTY period (no lines) it carries the optional
 * caller-supplied cross-check currency recorded for audit (no event is emitted).
 *
 * @param baseCurrency the company's base (functional) ISO-4217 currency, derived from the ledger
 *     (every line is in it)
 * @param usesIllustrativeRules whether any rolled-up posting was illustrative-placeholder-derived
 * @param lines the balanced trial-balance lines (P&amp;L lines + the equity closing line)
 */
public record CompanyTrialBalance(
    String baseCurrency, boolean usesIllustrativeRules, List<TrialBalanceLine> lines) {

  /** Whether the company has any postings for the period (an empty period closes to a no-op). */
  public boolean isEmpty() {
    return lines.isEmpty();
  }
}
