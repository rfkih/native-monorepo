package id.co.nativeapp.restaurant.register.dto;

/**
 * One settlement line on the POS daily summary (Z-report): the GROSS sales (before refunds) settled
 * to a tender in the register-session window. Distinct from {@link TenderExpected} (the drawer
 * expected-cash preview shown before close, which for CASH includes the opening float + cash
 * gift-card sales): this is pure gross sales per settlement type, so the tender lines foot to the
 * day's {@code total} (Σ {@code amount_minor}) and the standalone refunds line then nets to {@code
 * netSales}.
 *
 * @param tenderType {@code CASH} | {@code CARD} | {@code QRIS} | {@code ONLINE} | {@code GIFT_CARD}
 *     (the 5th, gift-card-redeemed-as-tender settlement — see {@link
 *     RegisterSummaryResponse#TENDER_GIFT_CARD})
 * @param salesMinor gross sales settled to the tender, integer minor units of the session currency
 *     (rule 8)
 */
public record TenderSalesLine(String tenderType, long salesMinor) {}
