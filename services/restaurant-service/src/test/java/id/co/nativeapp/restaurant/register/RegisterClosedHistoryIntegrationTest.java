package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.ClosedSessionSummaryResponse;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the manager/owner past closed-day history browse
 * ({@code GET /api/v1/register-sessions/closed}): it lists only CLOSED sessions for the outlet,
 * newest first, and each row's {@code netSalesMinor} equals that session's Z-report net over its
 * own {@code [openedAt, closedAt)} window (total − refunds), with the transaction count. An OPEN
 * session is excluded; an empty closed day nets 0; the list is tenant-isolated (RLS).
 */
@SpringBootTest
class RegisterClosedHistoryIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-closed-a@example.co.id";
  private static final String ACTOR_B = "cashier-closed-b@example.co.id";
  private static final LocalDate DAY = LocalDate.of(2026, 8, 7);

  @Autowired private RegisterSessionService registerSessionService;
  @Autowired private SaleService saleService;
  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private VoidRefundService voidRefundService;

  @Test
  void closedHistoryListsClosedDaysNewestFirstWithNetMatchingTheZReport() throws Exception {
    UUID outlet = UUID.fromString("cccccccc-1111-1111-1111-111111111111");

    // S1 — a CASH checkout (refundable via the real path) + a directly-rung CARD sale, then a
    // partial CASH refund, then CLOSE. Its net must reflect total − refund.
    UUID s1 = openSession(outlet, 0L);
    UUID esTehId = createItem(outlet, "Es Teh", 40_000L);
    CheckoutResult cashCheckout =
        asA(
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        outlet,
                        "closed-cash-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(esTehId, 1)),
                        new PaymentRequest(TenderType.CASH, 100_000L))));
    UUID cashPaymentId = cashCheckout.order().payment().paymentId();
    PriceBreakdown cardBreakdown = breakdown(50_000L, 0L, 2_500L, 5_250L, "IDR", false);
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    cardBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "closed-card-" + UUID.randomUUID(),
                    "CARD",
                    cardBreakdown)));
    long refundMinor = 5_000L;
    asA(() -> voidRefundService.refund(cashPaymentId, Money.ofMinor(refundMinor, "IDR")));
    closeSession(s1, 100_000L);

    // S2 — one QRIS sale, no refund; CLOSE.
    UUID s2 = openSession(outlet, 0L);
    PriceBreakdown qrisBreakdown = breakdown(40_000L, 0L, 1_750L, 3_675L, "IDR", false);
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    qrisBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "closed-qris-" + UUID.randomUUID(),
                    "QRIS",
                    qrisBreakdown)));
    closeSession(s2, 0L);

    // S3 — opened and closed with NO sales (empty closed day → net 0).
    UUID s3 = openSession(outlet, 0L);
    closeSession(s3, 0L);

    // S4 — opened, one sale, left OPEN: must be EXCLUDED by the status filter.
    UUID s4 = openSession(outlet, 0L);
    PriceBreakdown openBreakdown = breakdown(10_000L, 0L, 0L, 1_100L, "IDR", false);
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    openBreakdown.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "closed-open-" + UUID.randomUUID(),
                    "CASH",
                    openBreakdown)));

    RegisterSummaryResponse zS1 = asA(() -> registerSessionService.summarize(s1));
    RegisterSummaryResponse zS2 = asA(() -> registerSessionService.summarize(s2));

    List<ClosedSessionSummaryResponse> history =
        asA(() -> registerSessionService.closedHistory(outlet));

    // Only the three CLOSED sessions, newest first (opened_at DESC): S3, S2, S1. S4 (OPEN)
    // excluded.
    assertThat(history)
        .extracting(ClosedSessionSummaryResponse::sessionId)
        .containsExactly(s3, s2, s1);
    assertThat(history).extracting(ClosedSessionSummaryResponse::sessionId).doesNotContain(s4);

    ClosedSessionSummaryResponse rowS3 = history.get(0);
    assertThat(rowS3.businessDate()).isEqualTo(DAY);
    assertThat(rowS3.currency()).isEqualTo("IDR");
    assertThat(rowS3.openedAt()).isNotNull();
    assertThat(rowS3.closedAt()).isNotNull();
    assertThat(rowS3.netSalesMinor()).as("an empty closed day nets 0, not null").isZero();
    assertThat(rowS3.transactionCount()).isZero();

    // S2: no refund, so the list net == the Z-report net == the total.
    ClosedSessionSummaryResponse rowS2 = history.get(1);
    assertThat(rowS2.netSalesMinor()).isEqualTo(zS2.netSalesMinor());
    assertThat(rowS2.netSalesMinor()).isEqualTo(zS2.totalMinor());
    assertThat(rowS2.transactionCount()).isEqualTo(zS2.transactionCount());

    // S1: the refund was subtracted, so the list net == the Z-report net < the total.
    ClosedSessionSummaryResponse rowS1 = history.get(2);
    assertThat(zS1.refundsMinor()).as("S1 carried a partial refund").isEqualTo(refundMinor);
    assertThat(rowS1.netSalesMinor()).isEqualTo(zS1.netSalesMinor());
    assertThat(rowS1.netSalesMinor()).isEqualTo(zS1.totalMinor() - refundMinor);
    assertThat(rowS1.netSalesMinor()).isLessThan(zS1.totalMinor());
    assertThat(rowS1.transactionCount()).isEqualTo(zS1.transactionCount());
  }

  @Test
  void closedHistoryIsTenantIsolated() throws Exception {
    UUID outlet = UUID.fromString("cccccccc-2222-2222-2222-222222222222");
    UUID s = openSession(outlet, 0L);
    PriceBreakdown b = breakdown(30_000L, 0L, 0L, 3_300L, "IDR", false);
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    b.grandTotal().amountMinor(),
                    "IDR",
                    null,
                    "iso-" + UUID.randomUUID(),
                    "CASH",
                    b)));
    closeSession(s, 0L);

    // Tenant A sees its closed day…
    assertThat(asA(() -> registerSessionService.closedHistory(outlet))).isNotEmpty();

    // …tenant B sees nothing of tenant A's outlet (RLS scopes cash_register_session + sale).
    List<ClosedSessionSummaryResponse> asB =
        callAs(TENANT_B, ACTOR_B, () -> registerSessionService.closedHistory(outlet));
    assertThat(asB).isEmpty();
  }

  @Test
  void closedHistoryNetCountsAGiftCardSettledSaleAtItsFullAmount() throws Exception {
    UUID outlet = UUID.fromString("cccccccc-3333-3333-3333-333333333333");
    UUID s = openSession(outlet, 0L);

    // A CARD sale of 60,000 grand total, 20,000 of it settled by a redeemed gift card. The gift
    // card
    // is a SETTLEMENT tender, not a discount — net must count the FULL amount_minor (mirrors the
    // Z-report), so 60,000, not 40,000.
    long grandTotal = 60_000L;
    long giftCardRedeemed = 20_000L;
    PriceBreakdown cardBreakdown = breakdown(55_000L, 0L, 0L, 5_000L, "IDR", false);
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    outlet,
                    grandTotal,
                    "IDR",
                    null,
                    "gc-" + UUID.randomUUID(),
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
    closeSession(s, 0L);

    RegisterSummaryResponse z = asA(() -> registerSessionService.summarize(s));
    ClosedSessionSummaryResponse row =
        asA(() -> registerSessionService.closedHistory(outlet)).stream()
            .filter(r -> r.sessionId().equals(s))
            .findFirst()
            .orElseThrow();

    assertThat(row.netSalesMinor()).isEqualTo(grandTotal);
    assertThat(row.netSalesMinor()).isEqualTo(z.netSalesMinor());
    assertThat(row.transactionCount()).isEqualTo(1L);
  }

  // ---- helpers (mirroring RegisterSummaryIntegrationTest) ------------------------------------

  private UUID openSession(UUID outlet, long openingFloat) throws Exception {
    return asA(
        () ->
            registerSessionService
                .open(
                    new OpenSessionRequest(outlet, openingFloat, "IDR", DAY),
                    UUID.randomUUID().toString())
                .session()
                .id());
  }

  private void closeSession(UUID sessionId, long countedCash) throws Exception {
    asA(
        () ->
            registerSessionService.close(
                sessionId, new CloseSessionRequest(countedCash), "close:" + sessionId));
  }

  private UUID createItem(UUID outlet, String name, long priceMinor) throws Exception {
    return asA(
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(outlet, name, "DRINK", priceMinor, "IDR"))
                .id());
  }

  private static <T> T asA(Callable<T> action) {
    return callAs(TENANT_A, ACTOR_A, action);
  }

  private static <T> T callAs(String tenant, String actor, Callable<T> action) {
    try {
      return TenantContext.callAs(tenant, actor, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
