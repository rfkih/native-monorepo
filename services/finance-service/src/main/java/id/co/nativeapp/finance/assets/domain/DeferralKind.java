package id.co.nativeapp.finance.assets.domain;

/**
 * The two deferral shapes (Phase 6, ADR 0020) — mirror images of each other:
 *
 * <ul>
 *   <li>{@code PREPAID_EXPENSE} — an expense paid up front (e.g. a year's rent). Creation posts
 *       {@code Dr PREPAID_EXPENSE (1400) / Cr CASH_CLEARING (1900)}; each month amortizes {@code Dr
 *       EXPENSE (5000) / Cr PREPAID_EXPENSE (1400)}.
 *   <li>{@code DEFERRED_REVENUE} — revenue received up front (e.g. a year's subscription). Creation
 *       posts {@code Dr CASH_CLEARING (1900) / Cr DEFERRED_REVENUE (2400)}; each month recognizes
 *       {@code Dr DEFERRED_REVENUE (2400) / Cr REVENUE (4000)}.
 * </ul>
 */
public enum DeferralKind {
  PREPAID_EXPENSE,
  DEFERRED_REVENUE
}
