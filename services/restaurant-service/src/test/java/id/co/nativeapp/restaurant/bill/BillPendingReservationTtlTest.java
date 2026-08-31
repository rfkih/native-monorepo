package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 2026-08-31 audit #3 — an EXPIRED gateway reservation must not lock a bill forever.
 *
 * <p>Before the fix there was NO server-side expiry at all: a customer who walked away from a QRIS
 * left the bill's lines reserved indefinitely — unpayable by cash and (post-lockdown) also
 * uncancellable — with the frontend's explicit Abandon the only way out. {@code
 * BillWriter#releaseExpiredPendingReservation} now self-heals a PENDING payment older than {@code
 * native.bill.pending-reservation-ttl} at the two places a stuck operator actually pushes: cash
 * {@code payBill} and {@code cancelBill}.
 *
 * <p>TTL is pinned to {@code PT0S} here so a just-minted reservation counts as expired —
 * deterministic without clock manipulation. The production default (30m) keeps FRESH reservations
 * blocking, which {@code BillLockdownTest.cancelWithReservedLinesIsRejectedEvenForOwner} and the
 * payBill reserved-line exclusion pin under the default context.
 */
@SpringBootTest(properties = "native.bill.pending-reservation-ttl=PT0S")
class BillPendingReservationTtlTest extends PostgresRlsTestBase {

  private static final String TENANT = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "ttl-heal@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("99999999-9999-9999-9999-999999990001");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;

  @Test
  void cashPayBillSelfHealsAnExpiredGatewayReservation() throws Exception {
    UUID billId = openBillWithOneLine("Kopi Susu TTL");
    PaymentResponse pending = reserve(billId);
    assertThat(pending.status()).isEqualTo("PENDING");

    // Cash pay used to be locked out ("reserved by an in-flight gateway payment") forever; with
    // the reservation expired it is auto-abandoned and the cash check claims the lines.
    BillResponse paid =
        TenantContext.callAs(TENANT, ACTOR, () -> billService.payBill(billId, new PayBillRequest()));
    assertThat(paid.status()).isEqualTo("PAID");
  }

  @Test
  void cancelSelfHealsAnExpiredGatewayReservation() throws Exception {
    UUID billId = openBillWithOneLine("Teh Tarik TTL");
    reserve(billId);

    // Headerless caller (empty-roles-pass) may cancel; the expired reservation no longer blocks.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.cancelBill(billId);
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(billId));
    assertThat(after.status()).isEqualTo("CANCELLED");
  }

  private UUID openBillWithOneLine(String itemName) throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(BUSINESS, itemName, "MAIN", 30_000L, "IDR"))
                    .id());
    BillResponse opened =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "TTL Guest")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 1)))));
    return opened.id();
  }

  private PaymentResponse reserve(UUID billId) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.initiatePendingPayment(
                billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null)));
  }
}
