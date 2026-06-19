package id.co.nativeapp.finance.statements.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Balance Sheet (Laporan Posisi Keuangan) response — cumulative as of the given period end.
 *
 * <p>Derived entirely from the double-entry GL (no new tables, no migrations). All monetary amounts
 * are in minor units of the base currency (HR-8 — no float).
 *
 * <p><strong>Structure:</strong> ASSETS on one side; LIABILITIES + EQUITY (including computed
 * retained earnings) on the other. The statement is balanced: {@code totalAssetsMinor ==
 * totalLiabilitiesAndEquityMinor}. If it is not balanced, the service throws (defence-in-depth).
 *
 * <p><strong>Retained Earnings</strong> is computed on read as the cumulative net income across all
 * periods up to and including the as-of period (Σ REVENUE − Σ EXPENSE). It is NOT posted to the GL
 * — this is a read-side computation only. It appears as a separate equity line in {@code
 * equityLines} with account code {@code "3000-RETAINED-EARNINGS"}.
 *
 * <p><strong>SME-gated items (deferred):</strong> exact SAK-EMKM grouping/labels, comparative
 * columns. {@code usesIllustrativeRules} is the provisional-data flag for the dashboard (rule 9).
 *
 * @param asOf the as-of period {@code YYYY-MM} (the balance sheet date is the end of this period)
 * @param currency ISO-4217 base currency code
 * @param assetLines lines whose {@code accountType} is {@code "ASSET"}
 * @param liabilityLines lines whose {@code accountType} is {@code "LIABILITY"}
 * @param equityLines lines whose {@code accountType} is {@code "EQUITY"}, including the computed
 *     retained-earnings line
 * @param totalAssetsMinor Σ asset balances in minor units
 * @param totalLiabilitiesMinor Σ liability balances in minor units
 * @param totalEquityMinor Σ equity balances (incl. retained earnings) in minor units
 * @param totalLiabilitiesAndEquityMinor totalLiabilitiesMinor + totalEquityMinor
 * @param retainedEarningsMinor the computed retained earnings component (cumulative net income up
 *     to the as-of period); a separate disclosure so the dashboard can highlight it
 * @param usesIllustrativeRules whether any GL line used illustrative-placeholder rules
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BalanceSheetResponse(
    String asOf,
    String currency,
    List<BalanceSheetLineItem> assetLines,
    List<BalanceSheetLineItem> liabilityLines,
    List<BalanceSheetLineItem> equityLines,
    long totalAssetsMinor,
    long totalLiabilitiesMinor,
    long totalEquityMinor,
    long totalLiabilitiesAndEquityMinor,
    long retainedEarningsMinor,
    boolean usesIllustrativeRules) {}
