package id.co.nativeapp.restaurant.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException;
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
 * {@code ManualDiscountGuard} enforcement (ADR 0026): a positive manual {@code discountMinor}
 * requires {@code owner}/{@code manager} on checkout, pay-parked, and pay-bill — a cashier is
 * rejected with {@link ManualDiscountForbiddenException} (403 via {@code config.PromotionAdvice});
 * owner/manager and a headerless (dev-recipe / direct test) caller both pass.
 *
 * <p>Mirrors {@link id.co.nativeapp.restaurant.order.OutletEnforcementTest}'s {@code setRoles}
 * idiom: a {@link MockHttpServletRequest} is bound directly to {@link RequestContextHolder} so
 * {@link id.co.nativeapp.restaurant.config.ActorRolesProvider} reads the simulated {@code X-Roles}
 * gateway header — no MockMvc, no HTTP server.
 */
@SpringBootTest
class ManualDiscountRoleGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666666";
  private static final String ACTOR = "actor@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("66666666-6666-6666-6666-666666666601");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private BillService billService;

  private MockHttpServletRequest mockRequest;

  @BeforeEach
  void bindMockRequest() {
    mockRequest = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  private void setRoles(String roles) {
    mockRequest = new MockHttpServletRequest();
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

  private UUID createMenuItem() throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          CreateMenuItemRequest req =
              new CreateMenuItemRequest(BUSINESS_ID, "Kopi Susu", "BEVERAGE", 20_000L, "IDR");
          return menuService.createItem(req).id();
        });
  }

  // -----------------------------------------------------------------------
  // Checkout
  // -----------------------------------------------------------------------

  @Test
  void cashierWithAPositiveManualDiscountIsRejectedAtCheckout() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("cashier");

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            UUID.randomUUID().toString(),
            List.of(new OrderLineRequest(menuItemId, 1)),
            null,
            5_000L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void ownerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("owner");

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            UUID.randomUUID().toString(),
            List.of(new OrderLineRequest(menuItemId, 1)),
            null,
            5_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
    assertThat(result.order().totalMinor()).isEqualTo(15_000L); // 20,000 - 5,000, no tax/SC seeded
  }

  @Test
  void managerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("manager");

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            UUID.randomUUID().toString(),
            List.of(new OrderLineRequest(menuItemId, 1)),
            null,
            5_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void headerlessCallerWithAPositiveManualDiscountSucceedsAtCheckoutDevRecipeTrust()
      throws Exception {
    UUID menuItemId = createMenuItem();
    clearRoles(); // no X-Roles header at all — empty-roles-pass

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            UUID.randomUUID().toString(),
            List.of(new OrderLineRequest(menuItemId, 1)),
            null,
            5_000L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void cashierWithNoDiscountIsNotBlocked() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("cashier");

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            UUID.randomUUID().toString(),
            List.of(new OrderLineRequest(menuItemId, 1)));

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  // -----------------------------------------------------------------------
  // Pay-parked
  // -----------------------------------------------------------------------

  @Test
  void cashierWithAPositiveManualDiscountIsRejectedAtPayParked() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("owner"); // park itself is not gated — an owner parks a discounted cart
    UUID orderId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    orderService.park(
                        new ParkOrderRequest(
                            BUSINESS_ID,
                            UUID.randomUUID().toString(),
                            List.of(new OrderLineRequest(menuItemId, 1)),
                            null,
                            null,
                            3_000L)))
            .order()
            .orderId();

    setRoles("cashier"); // a different actor (or shift change) attempts to finalise it
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> orderService.payParked(orderId, new PayParkedRequest())))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  // -----------------------------------------------------------------------
  // Pay-bill
  // -----------------------------------------------------------------------

  @Test
  void cashierWithAPositiveManualDiscountIsRejectedAtPayBill() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("owner");
    UUID billId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, "Table 5")))
            .id();
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                billId, new AppendLinesRequest(List.of(new OrderLineRequest(menuItemId, 1)))));

    setRoles("cashier");
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> billService.payBill(billId, new PayBillRequest(null, 2_000L))))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void ownerWithAPositiveManualDiscountSucceedsAtPayBill() throws Exception {
    UUID menuItemId = createMenuItem();
    setRoles("owner");
    BillResponse opened =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, "Table 6")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(menuItemId, 1)))));

    BillResponse paid =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.payBill(opened.id(), new PayBillRequest(null, 2_000L)));
    assertThat(paid.status()).isEqualTo("PAID");
  }
}
