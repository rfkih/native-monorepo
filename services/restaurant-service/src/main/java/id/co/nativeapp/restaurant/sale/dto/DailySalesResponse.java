package id.co.nativeapp.restaurant.sale.dto;

import java.time.LocalDate;

/**
 * One outlet-local day of an outlet's sales ({@code GET /api/v1/sales/daily}) — the figure the
 * phone home leads with (ADR 0082). Money rule 8: integer minor units + ISO-4217 code, never a
 * float.
 *
 * @param businessDate the outlet-local calendar day (Asia/Jakarta, {@code OutletZone})
 * @param transactionCount the number of TENDERED sales rung that day ({@code tender_type IS NOT
 *     NULL} — the register summary's universe); a later-refunded sale still counts as rung
 * @param netSalesMinor Σ {@code sale.amount_minor} of those sales minus Σ {@code payment_refund.
 *     amount_minor} refunded THAT day (attributed by {@code refunded_at}, not by the sale's day),
 *     in minor units — what the day's Z-report would net; negative when a day only refunded
 * @param cogsMinor Σ {@code sale.cogs_minor} (the sale-time moving-average COGS fold, ADR 0067)
 *     across the day's costed sales, or {@code null} when none carried one — the client shows no
 *     margin, never a 100% one
 * @param costedTransactionCount how many of {@code transactionCount} carried a COGS fold; when it
 *     is below the count, a margin derived from {@code cogsMinor} is partial and must say so
 * @param currency the ISO-4217 currency code of every amount here
 * @param usesIllustrativeRules whether ANY of the day's sales was priced under illustrative (not
 *     yet official) tax rules — surfaces the amber badge, as every provisional figure does
 */
public record DailySalesResponse(
    LocalDate businessDate,
    long transactionCount,
    long netSalesMinor,
    Long cogsMinor,
    long costedTransactionCount,
    String currency,
    boolean usesIllustrativeRules) {}
