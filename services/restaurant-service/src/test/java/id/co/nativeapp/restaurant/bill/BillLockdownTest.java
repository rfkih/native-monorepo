package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.domain.BillHasPaidLinesException;
import id.co.nativeapp.restaurant.bill.domain.BillLinePaidException;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservedException;
import id.co.nativeapp.restaurant.bill.domain.BillMutationForbiddenException;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Open-bill lockdown (owner request 2026-08-31): once a bill is open the flow must end in payment
 * — it cannot be freely voided or trimmed by the cashier.
 *
 * <ul>
 *   <li>Cancel: a bill WITH lines requires {@code owner}/{@code manager}; an EMPTY bill (wrong
 *       table opened) may be cancelled by anyone.
 *   <li>Remove line: {@code owner}/{@code manager} only.
 *   <li>Hardening (any role): cancel is refused while any line is already PAID (recorded sales
 *       would be stranded — settle the remainder or use the return flow), and removing a PAID line
 *       is refused server-side.
 * </ul>
 *
 * <p>Role simulation mirrors {@link
 * id.co.nativeapp.restaurant.promotion.ManualDiscountRoleGuardTest}: a {@link
 * MockHttpServletRequest} bound to {@link RequestContextHolder} carries the simulated gateway
 * {@code X-Roles} header; a headerless caller (dev recipe / direct service tests) passes — the
 * empty-roles-pass semantics shared with {@code ManualDiscountGuard}.
 */
@SpringBootTest
class BillLockdownTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "actor@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("77777777-7777-7777-7777-777777770001");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;

  @BeforeEach
  void bindMockRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private void setRoles(String roles) {
    MockHttpServletRequest mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  private void clearRoles() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private UUID createMenuItem(String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS_ID, name, "FOOD", 25_000L, "IDR"))
                .id());
  }

  /** Opens a bill and appends one line per given menu item; returns the refreshed response. */
  private BillResponse openBillWithLines(UUID... menuItemIds) throws Exception {
    UUID billId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, "Meja 9")))
            .id();
    if (menuItemIds.length == 0) {
      return TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(billId));
    }
    List<OrderLineRequest> lines =
        java.util.Arrays.stream(menuItemIds).map(id -> new OrderLineRequest(id, 1)).toList();
    return TenantContext.callAs(
        TENANT, ACTOR, () -> billService.appendLines(billId, new AppendLinesRequest(lines)));
  }

  // -----------------------------------------------------------------------
  // Cancel — role gate
  // -----------------------------------------------------------------------

  @Test
  void cashierCancelOfBillWithLinesIsRejected() throws Exception {
    UUID menuItemId = createMenuItem("Nasi Goreng");
    setRoles("owner");
    BillResponse bill = openBillWithLines(menuItemId);

    setRoles("cashier");
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> {
                      billService.cancelBill(bill.id());
                      return null;
                    }))
        .isInstanceOf(BillMutationForbiddenException.class);

    // Still OPEN — nothing changed.
    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.status()).isEqualTo("OPEN");
  }

  @Test
  void cashierCancelOfEmptyBillSucceeds() throws Exception {
    setRoles("cashier");
    BillResponse bill = openBillWithLines();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.status()).isEqualTo("CANCELLED");
  }

  @Test
  void managerCancelOfBillWithLinesSucceeds() throws Exception {
    UUID menuItemId = createMenuItem("Es Teh");
    setRoles("manager");
    BillResponse bill = openBillWithLines(menuItemId);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.status()).isEqualTo("CANCELLED");
  }

  @Test
  void headerlessCancelOfBillWithLinesSucceedsDevRecipeTrust() throws Exception {
    UUID menuItemId = createMenuItem("Kopi Tubruk");
    clearRoles();
    BillResponse bill = openBillWithLines(menuItemId);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.status()).isEqualTo("CANCELLED");
  }

  // -----------------------------------------------------------------------
  // Cancel — paid-lines hardening (any role)
  // -----------------------------------------------------------------------

  @Test
  void cancelWithPaidLinesIsRejectedEvenForOwner() throws Exception {
    UUID itemA = createMenuItem("Ayam Bakar");
    UUID itemB = createMenuItem("Jus Alpukat");
    setRoles("owner");
    BillResponse bill = openBillWithLines(itemA, itemB);
    UUID firstLineId = bill.lines().get(0).id();

    // Split-pay ONE of the two lines — the bill stays OPEN with a recorded sale on line A.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.payBill(
                bill.id(), new PayBillRequest(null, null, List.of(firstLineId), null, null)));
    BillResponse partiallyPaid =
        TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(partiallyPaid.status()).isEqualTo("OPEN");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> {
                      billService.cancelBill(bill.id());
                      return null;
                    }))
        .isInstanceOf(BillHasPaidLinesException.class);
  }

  @Test
  void cancelWithReservedLinesIsRejectedEvenForOwner() throws Exception {
    UUID menuItemId = createMenuItem("Sate Ayam");
    setRoles("owner");
    BillResponse bill = openBillWithLines(menuItemId);

    // Reserve the lines under an in-flight gateway payment (PENDING QRIS) — cancelling now would
    // strand real PSP money at capture time.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.initiatePendingPayment(
                bill.id(),
                new PayBillRequest(
                    new id.co.nativeapp.restaurant.payment.dto.PaymentRequest(
                        id.co.nativeapp.restaurant.payment.domain.TenderType.QRIS, null),
                    null)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> {
                      billService.cancelBill(bill.id());
                      return null;
                    }))
        .isInstanceOf(BillLineReservedException.class);
  }

  // -----------------------------------------------------------------------
  // Remove line — role gate + paid hardening
  // -----------------------------------------------------------------------

  @Test
  void cashierRemoveLineIsRejected() throws Exception {
    UUID menuItemId = createMenuItem("Soto Ayam");
    setRoles("owner");
    BillResponse bill = openBillWithLines(menuItemId);
    UUID lineId = bill.lines().get(0).id();

    setRoles("cashier");
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> {
                      billService.removeLine(bill.id(), lineId);
                      return null;
                    }))
        .isInstanceOf(BillMutationForbiddenException.class);

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.lines()).hasSize(1);
  }

  @Test
  void managerRemoveLineSucceeds() throws Exception {
    UUID menuItemId = createMenuItem("Mie Ayam");
    setRoles("manager");
    BillResponse bill = openBillWithLines(menuItemId);
    UUID lineId = bill.lines().get(0).id();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.removeLine(bill.id(), lineId);
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.lines()).isEmpty();
  }

  @Test
  void headerlessRemoveLineSucceedsDevRecipeTrust() throws Exception {
    UUID menuItemId = createMenuItem("Bakso");
    clearRoles();
    BillResponse bill = openBillWithLines(menuItemId);
    UUID lineId = bill.lines().get(0).id();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          billService.removeLine(bill.id(), lineId);
          return null;
        });

    BillResponse after = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(bill.id()));
    assertThat(after.lines()).isEmpty();
  }

  @Test
  void removeOfPaidLineIsRejectedEvenForOwner() throws Exception {
    UUID itemA = createMenuItem("Gado-Gado");
    UUID itemB = createMenuItem("Teh Manis");
    setRoles("owner");
    BillResponse bill = openBillWithLines(itemA, itemB);
    UUID firstLineId = bill.lines().get(0).id();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.payBill(
                bill.id(), new PayBillRequest(null, null, List.of(firstLineId), null, null)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> {
                      billService.removeLine(bill.id(), firstLineId);
                      return null;
                    }))
        .isInstanceOf(BillLinePaidException.class);
  }
}
