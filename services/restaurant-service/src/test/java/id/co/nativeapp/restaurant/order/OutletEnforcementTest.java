package id.co.nativeapp.restaurant.order;

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
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
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
 * Enforcement tests for the Phase 5 outlet-assignment guard in {@link
 * id.co.nativeapp.restaurant.order.service.OrderWriter}.
 *
 * <p>Policy under test (signed-off):
 *
 * <ol>
 *   <li>Owner / manager role → bypass (no assignment required).
 *   <li>Cashier with an ACTIVE assignment for {@code (user_id, outlet_id)} → allowed.
 *   <li>Cashier with no active assignment, company has rows (scoping adopted) → 403.
 *   <li>Cashier with no assignment, company has ZERO rows (grandfather: scoping never adopted) →
 *       allowed.
 * </ol>
 *
 * <p>The guard ({@code OutletAccessGuard}) is shared by BOTH sale-recording write paths — orders
 * ({@code checkout}/{@code park}/{@code payParked}) and open bills ({@code open}/{@code payBill})
 * — so the bill tests below prove the bills flow cannot sidestep the order-path guard.
 *
 * <p>The {@link id.co.nativeapp.restaurant.config.ActorRolesProvider} reads the {@code X-Roles}
 * header from the current HTTP request. These tests bind a {@link MockHttpServletRequest} directly
 * to Spring's {@link RequestContextHolder} before each test to simulate the roles header that the
 * gateway stamps. This is the minimal setup: no MockMvc, no HTTP server, no {@code DevTenantFilter}
 * — just the raw request-attribute binding the provider relies on.
 */
@SpringBootTest
class OutletEnforcementTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OWNER_ACTOR = "owner@example.co.id";
  private static final String CASHIER_ACTOR = "cashier-01@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER_BUSINESS_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private BillService billService;
  @Autowired private UserOutletAssignmentRefService assignmentService;

  /**
   * Bind a fake HTTP request to {@link RequestContextHolder} before each test so that {@link
   * id.co.nativeapp.restaurant.config.ActorRolesProvider} can read the {@code X-Roles} header. Each
   * test sets its own roles header on {@link #mockRequest}; this setup provides the binding.
   */
  private MockHttpServletRequest mockRequest;

  @BeforeEach
  void bindMockRequest() {
    mockRequest = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  /**
   * Rebinds a FRESH request carrying the given {@code X-Roles} header. {@link
   * MockHttpServletRequest} headers are additive (a second addHeader does not replace the first),
   * so switching roles mid-test requires a new request bound to the holder.
   */
  private void setRoles(String roles) {
    mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  // -----------------------------------------------------------------------
  // Shared: create a menu item in the target business
  // -----------------------------------------------------------------------

  private UUID createMenuItem(UUID businessId) throws Exception {
    return TenantContext.callAs(
        TENANT,
        OWNER_ACTOR,
        () -> {
          CreateMenuItemRequest req =
              new CreateMenuItemRequest(businessId, "Kopi Susu", "BEVERAGE", 18_000L, "IDR");
          return menuService.createItem(req).id();
        });
  }

  private CheckoutRequest singleItemCheckout(UUID businessId, UUID menuItemId) {
    return new CheckoutRequest(
        businessId, UUID.randomUUID().toString(), List.of(new OrderLineRequest(menuItemId, 1)));
  }

  // -----------------------------------------------------------------------
  // 1. Owner bypasses outlet check
  // -----------------------------------------------------------------------

  @Test
  void ownerCanCheckoutAtAnyOutletWithoutAssignment() throws Exception {
    // Seed some rows so the company has non-zero scoping state (no grandfather).
    UUID assignmentId = UUID.randomUUID();
    UserOutletAssignmentEvent existing =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), assignmentId, CASHIER_ACTOR, TENANT,
            BUSINESS_ID, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(existing);

    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    // Owner role → bypass.
    mockRequest.addHeader("X-Roles", "owner");

    CheckoutResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void managerCanCheckoutAtAnyOutletWithoutAssignment() throws Exception {
    // Seed a row for a different user so grandfather doesn't apply.
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), UUID.randomUUID(), "other-cashier", TENANT,
            BUSINESS_ID, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);

    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    // Manager role → bypass.
    mockRequest.addHeader("X-Roles", "manager");

    // OWNER_ACTOR has no assignment — if manager bypass works, this succeeds.
    CheckoutResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  // -----------------------------------------------------------------------
  // 2. Cashier with active assignment → allowed
  // -----------------------------------------------------------------------

  @Test
  void cashierWithActiveAssignmentCanCheckout() throws Exception {
    // Assign CASHIER_ACTOR to BUSINESS_ID.
    UserOutletAssignmentEvent assignEvent =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), UUID.randomUUID(), CASHIER_ACTOR, TENANT,
            BUSINESS_ID, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(assignEvent);

    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    // Cashier role with a matching active assignment.
    mockRequest.addHeader("X-Roles", "cashier");

    CheckoutResult result =
        TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  // -----------------------------------------------------------------------
  // 3. Cashier not assigned to THIS outlet (but company has rows) → 403
  // -----------------------------------------------------------------------

  @Test
  void cashierNotAssignedToOutletIsRejectedWhenScopingIsAdopted() throws Exception {
    // Assign cashier to a DIFFERENT outlet.
    UserOutletAssignmentEvent seedOtherOutlet =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), UUID.randomUUID(), CASHIER_ACTOR, TENANT,
            OTHER_BUSINESS_ID, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seedOtherOutlet);

    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    // Cashier tries to ring at BUSINESS_ID but is only assigned to OTHER_BUSINESS_ID.
    mockRequest.addHeader("X-Roles", "cashier");

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  @Test
  void cashierUnassignedFromOutletIsRejectedWhenScopingIsAdopted() throws Exception {
    // First assign, then unassign (active = false).
    UUID assignmentId = UUID.randomUUID();
    UserOutletAssignmentEvent assign =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), assignmentId, CASHIER_ACTOR, TENANT,
            BUSINESS_ID, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(assign);
    UserOutletAssignmentEvent unassign =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), assignmentId, CASHIER_ACTOR, TENANT,
            BUSINESS_ID, "UNASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(2026, 7, 28).toEpochDay());
    assignmentService.apply(unassign);

    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    mockRequest.addHeader("X-Roles", "cashier");

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  // -----------------------------------------------------------------------
  // 4. Grandfather clause — zero rows in company → allow cashier
  // -----------------------------------------------------------------------

  @Test
  void cashierIsAllowedWhenCompanyHasZeroAssignmentRows() throws Exception {
    // user_outlet_assignment_ref is empty (truncated in resetTables()) → grandfather applies.
    UUID menuItemId = createMenuItem(BUSINESS_ID);
    CheckoutRequest req = singleItemCheckout(BUSINESS_ID, menuItemId);

    // Cashier with no assignment — but scoping never adopted → allow.
    mockRequest.addHeader("X-Roles", "cashier");

    CheckoutResult result =
        TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  // -----------------------------------------------------------------------
  // 5. Open-bill paths — the guard covers bills too (no sidestepping orders)
  // -----------------------------------------------------------------------

  private void assignCashierTo(UUID outletId) {
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), UUID.randomUUID(), CASHIER_ACTOR, TENANT,
            outletId, "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);
  }

  @Test
  void cashierNotAssignedCannotOpenBillWhenScopingIsAdopted() {
    // Cashier assigned elsewhere → scoping adopted, no access to BUSINESS_ID.
    assignCashierTo(OTHER_BUSINESS_ID);

    setRoles("cashier");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    CASHIER_ACTOR,
                    () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, "Guest 1"))))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  @Test
  void cashierNotAssignedCannotPayBillWhenScopingIsAdopted() throws Exception {
    // Scoping adopted (cashier assigned to a DIFFERENT outlet only).
    assignCashierTo(OTHER_BUSINESS_ID);
    UUID menuItemId = createMenuItem(BUSINESS_ID);

    // A manager opens the bill and appends a round at BUSINESS_ID (bypass — allowed).
    setRoles("manager");
    BillResponse bill =
        TenantContext.callAs(
            TENANT,
            OWNER_ACTOR,
            () -> {
              BillResponse opened =
                  billService.open(new OpenBillRequest(BUSINESS_ID, null, "Guest 2"));
              return billService.appendLines(
                  opened.id(),
                  new AppendLinesRequest(List.of(new OrderLineRequest(menuItemId, 1))));
            });

    // The unassigned cashier tries to take the payment → 403 at the money moment.
    setRoles("cashier");
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    CASHIER_ACTOR,
                    () -> billService.payBill(bill.id(), new PayBillRequest())))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  @Test
  void cashierGrandfatherCanOpenAndPayBillWhenCompanyHasZeroAssignmentRows() throws Exception {
    // user_outlet_assignment_ref is empty → scoping never adopted → bills flow untouched.
    UUID menuItemId = createMenuItem(BUSINESS_ID);

    setRoles("cashier");
    BillResponse paid =
        TenantContext.callAs(
            TENANT,
            CASHIER_ACTOR,
            () -> {
              BillResponse opened =
                  billService.open(new OpenBillRequest(BUSINESS_ID, null, "Guest 3"));
              billService.appendLines(
                  opened.id(),
                  new AppendLinesRequest(List.of(new OrderLineRequest(menuItemId, 1))));
              return billService.payBill(opened.id(), new PayBillRequest());
            });
    assertThat(paid.status()).isEqualTo("PAID");
  }
}
