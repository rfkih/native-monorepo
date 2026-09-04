package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.ItemSalesResponse;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.dto.TenderSalesLine;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the POS daily transaction summary (Z-report,
 * {@code GET /api/v1/register-sessions/{id}/summary}): the aggregate sales figures (V39 breakdown
 * snapshot summed via {@code RegisterSessionRepository#summarizeSales}) agree with the per-tender
 * GROSS settlement lines (which foot to the total, incl. the gift-card settlement line) and the
 * refunds/cash reconciliation over the SAME window, for an OPEN session (live X-report), a CLOSED
 * session (final Z-report), a gift-card split, and an empty window.
 */
@SpringBootTest
class RegisterSummaryIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-summary@example.co.id";
  private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

  @Autowired private RegisterSessionService registerSessionService;
  @Autowired private SaleService saleService;
  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private VoidRefundService voidRefundService;

  private static <T> T asTenant(Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void openSessionSummaryAggregatesAcrossTendersWithADiscountAnIllustrativeSaleAndARefund()
      throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");

    UUID sessionId =
        asTenant(
            () ->
                registerSessionService
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // Sale 1 — CASH, rung through the REAL checkout+payment path (server-computed pricing), so it
    // is later refunded via the real VoidRefundService.refund production path. Tendered generously
    // above any plausible total so change_minor >= 0 regardless of the seeded tax rate.
    UUID cashMenuItemId =
        asTenant(
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(outlet, "Es Teh", "DRINK", 40_000L, "IDR"))
                    .id());
    CheckoutResult cashCheckout =
        asTenant(
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        outlet,
                        "summary-cash-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(cashMenuItemId, 1)),
                        new PaymentRequest(TenderType.CASH, 100_000L))));
    PriceBreakdownResponse cashBreakdown = cashCheckout.order().breakdown();
    long cashGrandTotal = cashCheckout.order().totalMinor();
    UUID cashPaymentId = cashCheckout.order().payment().paymentId();

    // Sale 2 — CARD, a directly-rung sale carrying an ILLUSTRATIVE-rule breakdown, no discount.
    PriceBreakdown cardBreakdown = breakdown(50_000L, 0L, 2_500L, 5_250L, "IDR", true);
    asTenant(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    cardBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "summary-card-" + UUID.randomUUID(),
                    "CARD",
                    cardBreakdown)));

    // Sale 3 — QRIS, a directly-rung sale carrying a PROMO discount AND a loyalty redemption.
    // breakdown.discount() is the COMBINED 5,000 (promo 3,000 + loyalty 2,000) — the same shape
    // SaleWriter#stampBreakdownIfPresent decomposes to a promo-only 3,000 on the sale row.
    long qrisLoyaltyRedeemedMinor = 2_000L;
    PriceBreakdown qrisBreakdown = breakdown(40_000L, 5_000L, 1_750L, 3_675L, "IDR", false);
    asTenant(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    qrisBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "summary-qris-" + UUID.randomUUID(),
                    "QRIS",
                    qrisBreakdown,
                    UUID.randomUUID(),
                    200L,
                    qrisLoyaltyRedeemedMinor,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));

    // A FULL CASH refund against sale 1, via the real VoidRefundWriter production path — its
    // payment_refund row is what RegisterSessionRepository#sumCashRefunds/sumRefundsByTender read.
    // (2026-08-31 audit #2: partial refunds are rejected at the edge — full-only, once.)
    long refundMinor = cashGrandTotal;
    asTenant(() -> voidRefundService.refund(cashPaymentId, Money.ofMinor(refundMinor, "IDR")));

    RegisterSummaryResponse summary = asTenant(() -> registerSessionService.summarize(sessionId));

    assertThat(summary.sessionId()).isEqualTo(sessionId);
    assertThat(summary.businessId()).isEqualTo(outlet);
    assertThat(summary.status()).isEqualTo("OPEN");
    assertThat(summary.currency()).isEqualTo("IDR");
    assertThat(summary.transactionCount()).isEqualTo(3L);

    // Expected aggregate figures — computed by summing each sale's own (promo-only) breakdown, the
    // SAME components RegisterSessionRepository#summarizeSales sums from the V39 snapshot columns.
    long qrisPromoOnlyDiscount = qrisBreakdown.discount().amountMinor() - qrisLoyaltyRedeemedMinor;
    long expectedGross = cashBreakdown.subtotalMinor() + 50_000L + 40_000L;
    long expectedDiscount = cashBreakdown.discountMinor() + 0L + qrisPromoOnlyDiscount;
    long expectedLoyalty = cashBreakdown.loyaltyRedeemedMinor() + 0L + qrisLoyaltyRedeemedMinor;
    long expectedServiceCharge = cashBreakdown.serviceChargeMinor() + 2_500L + 1_750L;
    long expectedTax = cashBreakdown.taxMinor() + 5_250L + 3_675L;
    long expectedTotal = cashGrandTotal + 57_750L + 40_425L;
    long expectedRefunds = refundMinor;
    long expectedNetSales = expectedTotal - expectedRefunds;

    assertThat(summary.grossSalesMinor()).isEqualTo(expectedGross);
    assertThat(summary.discountMinor()).isEqualTo(expectedDiscount);
    assertThat(summary.loyaltyRedeemedMinor()).isEqualTo(expectedLoyalty);
    assertThat(summary.serviceChargeMinor()).isEqualTo(expectedServiceCharge);
    assertThat(summary.taxMinor()).isEqualTo(expectedTax);
    assertThat(summary.totalMinor()).isEqualTo(expectedTotal);
    assertThat(summary.refundsMinor()).isEqualTo(expectedRefunds);
    assertThat(summary.netSalesMinor()).isEqualTo(expectedNetSales);
    assertThat(summary.netSalesMinor()).isEqualTo(summary.totalMinor() - summary.refundsMinor());
    assertThat(summary.usesIllustrativeRules())
        .as("sale 2 (CARD) carried an illustrative-rule breakdown")
        .isTrue();

    // The reconciliation identity from the RegisterSummaryResponse javadoc holds in aggregate.
    assertThat(
            summary.grossSalesMinor()
                - summary.discountMinor()
                - summary.loyaltyRedeemedMinor()
                + summary.serviceChargeMinor()
                + summary.taxMinor())
        .as("gross - discount - loyaltyRedeemed + serviceCharge + tax == total")
        .isEqualTo(summary.totalMinor());

    // Per-tender GROSS sales (before refunds): CASH is the full sale amount (the refund shows in
    // the
    // separate refunds line, not netted into the tender), CARD/QRIS their charged amounts, ONLINE
    // none. This is what makes the settlement block foot to the total.
    assertThat(tenderSales(summary, "CASH")).isEqualTo(cashGrandTotal);
    assertThat(tenderSales(summary, "CARD")).isEqualTo(57_750L);
    assertThat(tenderSales(summary, "QRIS")).isEqualTo(40_425L);
    assertThat(tenderSales(summary, "ONLINE")).isZero();
    // Settlement side foots to the total (no gift card here → the four tender lines sum to total).
    assertThat(tenderSalesTotal(summary))
        .as("Σ per-tender gross == total (settlement block foots)")
        .isEqualTo(summary.totalMinor());

    // OPEN session: no cash reconciliation snapshot yet, but the LIVE expected cash is populated.
    assertThat(summary.countedCashMinor()).isNull();
    assertThat(summary.overShortMinor()).isNull();
    assertThat(summary.expectedCashMinor()).isEqualTo(cashGrandTotal - refundMinor);
  }

  @Test
  void closedSessionSummaryReturnsTheStoredCashReconciliationOverTheClosedWindow()
      throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222");
    long openingFloat = 5_000L;

    RegisterSessionResponse opened =
        asTenant(
            () ->
                registerSessionService
                    .open(
                        new OpenSessionRequest(outlet, openingFloat, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session());
    UUID sessionId = opened.id();

    // One CASH sale: subtotal 20,000 - discount 0 + SC 1,000 + tax 2,100 = grandTotal 23,100.
    PriceBreakdown cashBreakdown = breakdown(20_000L, 0L, 1_000L, 2_100L, "IDR", false);
    asTenant(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    cashBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "summary-close-cash-" + UUID.randomUUID(),
                    "CASH",
                    cashBreakdown)));

    // expected = float 5,000 + cashSales 23,100 = 28,100; counted 28,500 -> over-short = +400.
    long countedCash = 28_500L;
    RegisterSessionResponse closed =
        asTenant(
            () ->
                registerSessionService.close(
                    sessionId, new CloseSessionRequest(countedCash), "summary-close:" + sessionId));
    assertThat(closed.status()).isEqualTo("CLOSED");

    RegisterSummaryResponse summary = asTenant(() -> registerSessionService.summarize(sessionId));

    assertThat(summary.status()).isEqualTo("CLOSED");
    // Millisecond-truncated comparison: opened/closed are re-read here via the native-query
    // projection, whose TIMESTAMPTZ column round-trips through Postgres' microsecond storage
    // precision (rounded, not truncated) — a sub-microsecond difference from the in-memory
    // Instant captured at open()/close() time is not a semantic difference in the WINDOW itself.
    assertThat(summary.openedAt().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(opened.openedAt().truncatedTo(ChronoUnit.MILLIS));
    assertThat(summary.asOf().truncatedTo(ChronoUnit.MILLIS))
        .as("a CLOSED session's window upper bound is closedAt, not \"now\"")
        .isEqualTo(closed.closedAt().truncatedTo(ChronoUnit.MILLIS));
    assertThat(summary.transactionCount()).isEqualTo(1L);
    assertThat(summary.totalMinor()).isEqualTo(23_100L);
    assertThat(summary.openingFloatMinor()).isEqualTo(openingFloat);
    assertThat(summary.expectedCashMinor()).isEqualTo(28_100L);
    assertThat(summary.countedCashMinor()).isEqualTo(countedCash);
    assertThat(summary.overShortMinor()).isEqualTo(400L);
  }

  @Test
  void giftCardSplitSaleAddsAFifthSettlementLineThatKeepsTheBlockFooting() throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-4444-4444-4444-444444444444");

    UUID sessionId =
        asTenant(
            () ->
                registerSessionService
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // A CARD sale of 60,000 grand total, 20,000 of it settled by a redeemed gift card: the CARD
    // clearing leg is amount − giftCard = 40,000, and the 20,000 is the 5th ("GIFT_CARD")
    // settlement
    // line. Without that line the settlement block would under-foot the total by the redeemed
    // value.
    long grandTotal = 60_000L;
    long giftCardRedeemed = 20_000L;
    PriceBreakdown cardBreakdown =
        breakdown(55_000L, 0L, 0L, 5_000L, "IDR", false); // grandTotal 60k
    asTenant(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    grandTotal,
                    "IDR",
                    null,
                    "summary-giftcard-" + UUID.randomUUID(),
                    "CARD",
                    cardBreakdown,
                    null, // loyaltyMemberId
                    null, // loyaltyRedeemedPoints
                    null, // loyaltyRedeemedMinor
                    UUID.randomUUID(), // giftCardId
                    giftCardRedeemed, // giftCardRedeemedMinor
                    null, // channel
                    null, // soldByUserId
                    null, // cogsMinor
                    null))); // cogsCurrency

    RegisterSummaryResponse summary = asTenant(() -> registerSessionService.summarize(sessionId));

    assertThat(summary.totalMinor()).isEqualTo(grandTotal);
    assertThat(tenderSales(summary, "CARD")).isEqualTo(grandTotal - giftCardRedeemed); // 40,000
    assertThat(tenderSales(summary, "GIFT_CARD")).isEqualTo(giftCardRedeemed); // 20,000
    assertThat(tenderSalesTotal(summary))
        .as("CARD 40,000 + GIFT_CARD 20,000 == total 60,000 (settlement foots with a gift card)")
        .isEqualTo(summary.totalMinor());
  }

  @Test
  void freshlyOpenedSessionWithNoSalesReturnsZerosNotNulls() throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333");
    long openingFloat = 10_000L;

    UUID sessionId =
        asTenant(
            () ->
                registerSessionService
                    .open(
                        new OpenSessionRequest(outlet, openingFloat, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    RegisterSummaryResponse summary = asTenant(() -> registerSessionService.summarize(sessionId));

    assertThat(summary.status()).isEqualTo("OPEN");
    assertThat(summary.transactionCount()).isZero();
    assertThat(summary.grossSalesMinor()).isZero();
    assertThat(summary.discountMinor()).isZero();
    assertThat(summary.loyaltyRedeemedMinor()).isZero();
    assertThat(summary.serviceChargeMinor()).isZero();
    assertThat(summary.taxMinor()).isZero();
    assertThat(summary.totalMinor()).isZero();
    assertThat(summary.refundsMinor()).isZero();
    assertThat(summary.netSalesMinor()).isZero();
    assertThat(summary.usesIllustrativeRules()).isFalse();
    assertThat(tenderSales(summary, "CASH")).isZero();
    assertThat(tenderSales(summary, "CARD")).isZero();
    assertThat(tenderSales(summary, "QRIS")).isZero();
    assertThat(tenderSales(summary, "ONLINE")).isZero();
    assertThat(tenderSalesTotal(summary)).isZero();
    assertThat(summary.openingFloatMinor()).isEqualTo(openingFloat);
    assertThat(summary.expectedCashMinor()).isEqualTo(openingFloat);
    assertThat(summary.countedCashMinor()).isNull();
    assertThat(summary.overShortMinor()).isNull();
  }

  @Test
  void itemSalesReturnsPerItemUnitsAndGrossRevenueOverTheWindow() throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-5555-5555-5555-555555555555");

    UUID burgerId =
        asTenant(
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(outlet, "Burger", "DRINK", 30_000L, "IDR"))
                    .id());
    UUID kebabId =
        asTenant(
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(outlet, "Kebab", "DRINK", 25_000L, "IDR"))
                    .id());

    // Two CASH checkouts through the REAL path: Burger×2 + Kebab×1, then Burger×1 in a SECOND sale
    // —
    // so Burger's 3 units span two sales (proves cross-sale aggregation, not just per-order).
    asTenant(
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet,
                    "items-a-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(burgerId, 2), new OrderLineRequest(kebabId, 1)),
                    new PaymentRequest(TenderType.CASH, 200_000L))));
    asTenant(
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    outlet,
                    "items-b-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(burgerId, 1)),
                    new PaymentRequest(TenderType.CASH, 100_000L))));

    List<ItemSalesResponse> items =
        asTenant(
            () ->
                orderService.itemSales(
                    outlet, Instant.EPOCH, Instant.parse("2999-12-31T00:00:00Z")));

    // Best-sellers first: Burger (3 units across the two sales), then Kebab (1). Revenue = GROSS
    // line
    // totals: Burger 3 × 30,000 = 90,000; Kebab 1 × 25,000 = 25,000. Name is the sold-time
    // snapshot.
    assertThat(items).hasSize(2);
    assertThat(items.get(0).menuItemId()).isEqualTo(burgerId);
    assertThat(items.get(0).name()).isEqualTo("Burger");
    assertThat(items.get(0).soldQty()).isEqualTo(3L);
    assertThat(items.get(0).revenueMinor()).isEqualTo(90_000L);
    assertThat(items.get(1).menuItemId()).isEqualTo(kebabId);
    assertThat(items.get(1).name()).isEqualTo("Kebab");
    assertThat(items.get(1).soldQty()).isEqualTo(1L);
    assertThat(items.get(1).revenueMinor()).isEqualTo(25_000L);

    // Window EXCLUSION: a window entirely BEFORE these sales returns nothing (proves the time
    // filter).
    List<ItemSalesResponse> none =
        asTenant(
            () ->
                orderService.itemSales(
                    outlet, Instant.EPOCH, Instant.parse("2020-01-01T00:00:00Z")));
    assertThat(none).isEmpty();
  }

  /** The GROSS sales settled to a tender on the summary (0 if that tender line is absent). */
  private static long tenderSales(RegisterSummaryResponse summary, String tenderType) {
    return summary.tenders().stream()
        .filter(t -> t.tenderType().equals(tenderType))
        .findFirst()
        .map(TenderSalesLine::salesMinor)
        .orElse(0L);
  }

  /**
   * Σ of every settlement line — must foot to the day's total (revenue-side and settlement-side).
   */
  private static long tenderSalesTotal(RegisterSummaryResponse summary) {
    return summary.tenders().stream().mapToLong(TenderSalesLine::salesMinor).sum();
  }

  /** Builds a {@link PriceBreakdown} whose reconciliation identity holds by construction. */
  private static PriceBreakdown breakdown(
      long subtotal,
      long discount,
      long serviceCharge,
      long tax,
      String currency,
      boolean illustrative) {
    long taxableBase = subtotal - discount;
    long grandTotal = taxableBase + serviceCharge + tax;
    return new PriceBreakdown(
        Money.ofMinor(subtotal, currency),
        Money.ofMinor(discount, currency),
        Money.ofMinor(taxableBase, currency),
        Money.ofMinor(serviceCharge, currency),
        Money.ofMinor(tax, currency),
        Money.ofMinor(grandTotal, currency),
        "test-rule-v1",
        illustrative);
  }
}
