package id.co.nativeapp.restaurant.register.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The POS daily transaction summary (Z-report) for one register session — printed at close (the
 * final Z-report over {@code [openedAt, closedAt)}) or any time during the day (a live X-report
 * over {@code [openedAt, now)}). Works for both an OPEN and a CLOSED session.
 *
 * <p><strong>Reporting only.</strong> Finance's ledger remains the authoritative statutory figure.
 * The tax line is PB1 ("Pajak Restoran"), NOT PPN; whenever {@link #usesIllustrativeRules} is true
 * the printed tax must be badged "estimasi" (the seeded rate is an illustrative placeholder until
 * an accounting SME replaces it). The document is a management summary — never a Faktur Pajak.
 *
 * <p><strong>Both decompositions of {@code totalMinor} foot</strong> (all minor units, rule 8). The
 * REVENUE side: {@code grossSalesMinor − discountMinor − loyaltyRedeemedMinor + serviceChargeMinor
 * + taxMinor == totalMinor} (summed from the per-sale V39 snapshot, never re-derived). The
 * SETTLEMENT side: {@code Σ tenders[].salesMinor == totalMinor} — the per-tender GROSS lines plus
 * the gift-card line (when present) account for every rupiah charged. {@code totalMinor} is the
 * grand total charged, GROSS of refunds; {@code netSalesMinor = totalMinor − refundsMinor}.
 *
 * @param sessionId the register session this summary covers
 * @param businessId the outlet
 * @param status {@code OPEN} (live X-report) or {@code CLOSED} (final Z-report)
 * @param businessDate the session's business date
 * @param currency ISO-4217 code of the drawer
 * @param openedAt when the drawer opened (window lower bound)
 * @param asOf the window upper bound — {@code closedAt} for a CLOSED session, else "now"
 * @param transactionCount number of tendered sales in the window
 * @param grossSalesMinor Σ subtotal (pre-discount menu price)
 * @param discountMinor Σ promo-only discount (deduction)
 * @param loyaltyRedeemedMinor Σ loyalty-points contra-revenue (deduction)
 * @param serviceChargeMinor Σ service charge (addition)
 * @param taxMinor Σ tax / PB1 (addition)
 * @param totalMinor Σ grand total charged, gross of refunds
 * @param refundsMinor total refunds paid in the window (all tenders)
 * @param netSalesMinor {@code totalMinor − refundsMinor}
 * @param usesIllustrativeRules true when any sale used an illustrative tax/SC rule → badge the tax
 *     line "estimasi"
 * @param tenders per-tender GROSS sales (CASH/CARD/QRIS/ONLINE, plus a {@link #TENDER_GIFT_CARD}
 *     line when gift cards were redeemed) — Σ {@code salesMinor} == {@code totalMinor}
 * @param openingFloatMinor the counted opening float
 * @param expectedCashMinor expected cash in the drawer (live for OPEN, snapshotted for CLOSED)
 * @param countedCashMinor the physical recount at close — {@code null} while OPEN
 * @param overShortMinor signed cash over/short at close — {@code null} while OPEN
 */
public record RegisterSummaryResponse(
    UUID sessionId,
    UUID businessId,
    String status,
    LocalDate businessDate,
    String currency,
    Instant openedAt,
    Instant asOf,
    long transactionCount,
    long grossSalesMinor,
    long discountMinor,
    long loyaltyRedeemedMinor,
    long serviceChargeMinor,
    long taxMinor,
    long totalMinor,
    long refundsMinor,
    long netSalesMinor,
    boolean usesIllustrativeRules,
    List<TenderSalesLine> tenders,
    long openingFloatMinor,
    Long expectedCashMinor,
    Long countedCashMinor,
    Long overShortMinor) {

  /**
   * The synthetic settlement-type code for gift-card-redeemed-as-tender on a {@link
   * TenderSalesLine} — NOT a {@code payment.domain.TenderType} enum value (gift card is a
   * redemption, not a payment tender), so it is carried as this string and rendered from its own
   * i18n key on the client.
   */
  public static final String TENDER_GIFT_CARD = "GIFT_CARD";
}
