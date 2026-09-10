package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.config.OutletZone;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import id.co.nativeapp.restaurant.sale.dto.DailySalesResponse;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers acceptance test for {@code GET /api/v1/sales/daily} — the phone home's per-day
 * sales read ({@code SaleRepository.findDailySummary}, ADR 0082). Mirrors {@link
 * ChannelSalesSummaryAcceptanceTest}: real sales through {@link SaleService#recordSale} with
 * explicit {@code occurredAt}s, read back through the service.
 *
 * <p>The day is the OUTLET-LOCAL one ({@link OutletZone}, Asia/Jakarta): a sale at 16:30 UTC is
 * 23:30 WIB and belongs to that day; one an hour later is 00:30 WIB the NEXT day. The universe is
 * the register summary's — tendered sales only ({@code tender_type IS NOT NULL}) — net of the
 * append-only {@code payment_refund} ledger, so a day's figure is exactly what its Z-report would
 * net. {@code cogs_minor} (V44) is summed alongside so the home can show a gross margin, with the
 * count of costed sales so a partially-costed day is never presented as fully costed.
 */
@SpringBootTest
class DailySalesAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-aaaaaaaaaaaa";
  private static final String TENANT_B = "11111111-1111-1111-1111-bbbbbbbbbbbb";
  private static final String ACTOR_A = "manager-a@example.co.id";
  private static final String ACTOR_B = "manager-b@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-dddddddddddd");

  @Autowired private SaleService saleService;
  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private VoidRefundService voidRefundService;

  @Test
  void daysAreOutletLocalAndOnlyTenderedSalesInTheWindowCount() throws Exception {
    // 23:30 WIB on the 8th and 00:30 WIB on the 9th — one hour apart in UTC, different days.
    recordCash("d-1", 50_000L, "2026-06-08T16:30:00Z");
    recordCash("d-2", 70_000L, "2026-06-08T17:30:00Z");
    // A second sale on the 9th, this one costed (cogs 30,000 of its 100,000).
    recordCosted("d-3", 100_000L, 30_000L, "2026-06-09T05:00:00Z");
    // A NULL-tender row (gift-card fully settled) on the 9th — not part of the tendered universe.
    recordNoTender("d-4", 999_000L, "2026-06-09T06:00:00Z");
    // A sale on the 10th — outside the requested window, must not appear.
    recordCash("d-5", 999_000L, "2026-06-10T03:00:00Z");

    List<DailySalesResponse> days =
        asA(
            () ->
                saleService.dailySummary(
                    BUSINESS, LocalDate.of(2026, 6, 7), LocalDate.of(2026, 6, 9)));

    // The 7th had nothing — absent, not a zero row; the client zero-fills.
    assertThat(days)
        .extracting(DailySalesResponse::businessDate)
        .containsExactly(LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 9));

    DailySalesResponse eighth = days.get(0);
    assertThat(eighth.transactionCount()).isEqualTo(1L);
    assertThat(eighth.netSalesMinor()).isEqualTo(50_000L);
    assertThat(eighth.cogsMinor()).isNull();
    assertThat(eighth.costedTransactionCount()).isZero();
    assertThat(eighth.currency()).isEqualTo("IDR");
    assertThat(eighth.usesIllustrativeRules()).isFalse();

    DailySalesResponse ninth = days.get(1);
    assertThat(ninth.transactionCount()).as("the NULL-tender row is excluded").isEqualTo(2L);
    assertThat(ninth.netSalesMinor()).isEqualTo(170_000L);
    assertThat(ninth.cogsMinor()).isEqualTo(30_000L);
    assertThat(ninth.costedTransactionCount()).as("one of the two sales was costed").isEqualTo(1L);
  }

  @Test
  void aRefundNetsAgainstTheDayItWasRefundedOn() throws Exception {
    // A CASH checkout through the real path (refundable), refunded in full right away — both land
    // on today's outlet-local day, so today's net is the CARD sale alone.
    UUID esTehId =
        asA(
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS, "Es Teh", "DRINK", 40_000L, "IDR"))
                    .id());
    CheckoutResult checkout =
        asA(
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "daily-cash-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(esTehId, 1)),
                        new PaymentRequest(TenderType.CASH, 100_000L))));
    long checkoutMinor = checkout.order().payment().amountMinor();
    UUID paymentId = checkout.order().payment().paymentId();
    asA(() -> voidRefundService.refund(paymentId, Money.ofMinor(checkoutMinor, "IDR")));
    // A CARD sale "now" as well.
    asA(
        () ->
            saleService.recordSale(
                new RecordSaleCommand(
                    BUSINESS, 25_000L, "IDR", null, "daily-card-" + UUID.randomUUID(), "CARD")));

    LocalDate today = OutletZone.today();
    List<DailySalesResponse> days =
        asA(() -> saleService.dailySummary(BUSINESS, today.minusDays(1), today.plusDays(1)));

    DailySalesResponse row =
        days.stream().filter(d -> d.businessDate().equals(today)).findFirst().orElseThrow();
    assertThat(row.transactionCount()).as("the refunded sale still counts as rung").isEqualTo(2L);
    assertThat(row.netSalesMinor())
        .as("total − refund")
        .isEqualTo(checkoutMinor + 25_000L - checkoutMinor);
  }

  @Test
  void aDifferentTenantNeverSeesAnotherTenantsDays() throws Exception {
    recordCash("iso-a", 100_000L, "2026-06-08T10:00:00Z");
    RecordSaleCommand forB =
        new RecordSaleCommand(
            BUSINESS, 40_000L, "IDR", Instant.parse("2026-06-08T11:00:00Z"), "iso-b", "CASH");
    TenantContext.callAs(TENANT_B, ACTOR_B, () -> saleService.recordSale(forB));

    LocalDate day = LocalDate.of(2026, 6, 8);
    List<DailySalesResponse> daysA = asA(() -> saleService.dailySummary(BUSINESS, day, day));
    List<DailySalesResponse> daysB =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> saleService.dailySummary(BUSINESS, day, day));

    assertThat(daysA)
        .singleElement()
        .satisfies(r -> assertThat(r.netSalesMinor()).isEqualTo(100_000L));
    assertThat(daysB)
        .singleElement()
        .satisfies(r -> assertThat(r.netSalesMinor()).isEqualTo(40_000L));
  }

  // ---- helpers ------------------------------------------------------------------------------

  private void recordCash(String key, long amountMinor, String occurredAt) throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(BUSINESS, amountMinor, "IDR", Instant.parse(occurredAt), key, "CASH");
    asA(() -> saleService.recordSale(command));
  }

  private void recordNoTender(String key, long amountMinor, String occurredAt) throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(BUSINESS, amountMinor, "IDR", Instant.parse(occurredAt), key);
    asA(() -> saleService.recordSale(command));
  }

  private void recordCosted(String key, long amountMinor, long cogsMinor, String occurredAt)
      throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            BUSINESS,
            amountMinor,
            "IDR",
            Instant.parse(occurredAt),
            key,
            "CARD",
            null, // breakdown
            null, // loyaltyMemberId
            null, // loyaltyRedeemedPoints
            null, // loyaltyRedeemedMinor
            null, // giftCardId
            null, // giftCardRedeemedMinor
            null, // channel
            null, // soldByUserId
            cogsMinor,
            "IDR");
    asA(() -> saleService.recordSale(command));
  }

  private static <T> T asA(Callable<T> action) throws Exception {
    return TenantContext.callAs(TENANT_A, ACTOR_A, action);
  }
}
