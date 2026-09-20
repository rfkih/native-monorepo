package id.co.nativeapp.restaurant.register;

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
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionHasOpenBillsException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ADR 0086 — no open bill survives a register close. Against real Postgres (what the mocked writer
 * test cannot prove): the {@code COUNT(*) FROM bill} precondition is valid SQL under RLS, it sees
 * exactly the outlet's OPEN bills (not PAID, not CANCELLED, not another outlet's, not another
 * tenant's), the refusal writes nothing and leaves the close key unconsumed so the SAME request
 * closes cleanly once the bills are settled, and a bill has to be settled by a person — an empty
 * bill blocks the close just like a served one until someone cancels it.
 *
 * <p>Role simulation follows {@code BillLockdownTest}: a {@link MockHttpServletRequest} bound to
 * {@link RequestContextHolder} carries the simulated gateway {@code X-Roles}.
 */
@SpringBootTest
class RegisterCloseOpenBillsGuardIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_TENANT = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier-open-bills@example.co.id";
  private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

  @Autowired private RegisterSessionService registerService;
  @Autowired private BillService billService;
  @Autowired private MenuService menuService;

  @BeforeEach
  void bindHeaderlessRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void setRoles(String roles) {
    MockHttpServletRequest mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  private static <T> T asTenant(Callable<T> action) {
    return callAs(TENANT, action);
  }

  private static <T> T callAs(String tenant, Callable<T> action) {
    try {
      return TenantContext.callAs(tenant, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID openSession(String tenant, UUID outlet) {
    return callAs(
        tenant,
        () ->
            registerService
                .open(new OpenSessionRequest(outlet, 0L, "IDR", DAY), UUID.randomUUID().toString())
                .session()
                .id());
  }

  private RegisterSessionResponse close(UUID sessionId) {
    // The console's stable key shape — the SAME key must retry after the guard refused.
    return asTenant(
        () -> registerService.close(sessionId, new CloseSessionRequest(0L), "close:" + sessionId));
  }

  private UUID createMenuItem(UUID outlet, String name) {
    return asTenant(
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(outlet, name, "FOOD", 25_000L, "IDR"))
                .id());
  }

  private BillResponse openBill(String tenant, UUID outlet, UUID... menuItemIds) {
    UUID billId =
        callAs(tenant, () -> billService.open(new OpenBillRequest(outlet, null, "Meja 3"))).id();
    if (menuItemIds.length == 0) {
      return callAs(tenant, () -> billService.getById(billId));
    }
    List<OrderLineRequest> lines =
        java.util.Arrays.stream(menuItemIds).map(id -> new OrderLineRequest(id, 1)).toList();
    return callAs(tenant, () -> billService.appendLines(billId, new AppendLinesRequest(lines)));
  }

  private static UUID outlet() {
    return UUID.randomUUID();
  }

  @Test
  void anEmptyOpenBillBlocksTheCloseUntilSomeoneCancelsIt_thenTheSameKeyCloses() {
    UUID outlet = outlet();
    UUID sessionId = openSession(TENANT, outlet);
    BillResponse bill = openBill(TENANT, outlet);

    // The preview names the precondition before the cashier counts.
    assertThat(asTenant(() -> registerService.expectedBreakdown(sessionId)).openBillCount())
        .isEqualTo(1L);

    assertThatThrownBy(() -> close(sessionId))
        .isInstanceOf(RegisterSessionHasOpenBillsException.class)
        .satisfies(
            ex ->
                assertThat(((RegisterSessionHasOpenBillsException) ex).getOpenBillCount())
                    .isEqualTo(1L));

    // Nothing was written: still OPEN, still the current session.
    RegisterSessionResponse stillOpen =
        asTenant(() -> registerService.current(outlet)).orElseThrow();
    assertThat(stillOpen.id()).isEqualTo(sessionId);
    assertThat(stillOpen.status()).isEqualTo("OPEN");

    // An EMPTY bill is cancellable by anyone (open-bill lockdown) — headerless caller here.
    asTenant(
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });

    // The SAME close key now closes — the refusal did not consume it.
    RegisterSessionResponse closed = close(sessionId);
    assertThat(closed.id()).isEqualTo(sessionId);
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(asTenant(() -> registerService.current(outlet))).isEmpty();
  }

  @Test
  void aPaidBillDoesNotBlockTheClose() {
    UUID outlet = outlet();
    UUID sessionId = openSession(TENANT, outlet);
    UUID menuItemId = createMenuItem(outlet, "Nasi Goreng");
    BillResponse bill = openBill(TENANT, outlet, menuItemId);

    assertThat(asTenant(() -> registerService.expectedBreakdown(sessionId)).openBillCount())
        .isEqualTo(1L);

    // Pay every line (no tender = cash-equivalent record of the check) → the bill is PAID.
    asTenant(() -> billService.payBill(bill.id(), new PayBillRequest()));
    assertThat(asTenant(() -> billService.getById(bill.id())).status()).isEqualTo("PAID");

    assertThat(asTenant(() -> registerService.expectedBreakdown(sessionId)).openBillCount())
        .isZero();
    assertThat(close(sessionId).status()).isEqualTo("CLOSED");
  }

  @Test
  void aBillWithLinesCancelledByAnOwnerDoesNotBlockTheClose() {
    UUID outlet = outlet();
    UUID sessionId = openSession(TENANT, outlet);
    UUID menuItemId = createMenuItem(outlet, "Es Teh");
    BillResponse bill = openBill(TENANT, outlet, menuItemId);

    assertThatThrownBy(() -> close(sessionId))
        .isInstanceOf(RegisterSessionHasOpenBillsException.class);

    // A bill WITH lines needs owner/manager to cancel — the close never does it for them.
    setRoles("owner");
    asTenant(
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });
    assertThat(asTenant(() -> billService.getById(bill.id())).status()).isEqualTo("CANCELLED");

    assertThat(close(sessionId).status()).isEqualTo("CLOSED");
  }

  @Test
  void anotherOutletsOpenBillDoesNotBlockThisOutletsClose() {
    UUID outlet = outlet();
    UUID otherOutlet = outlet();
    UUID sessionId = openSession(TENANT, outlet);
    openBill(TENANT, otherOutlet);

    assertThat(asTenant(() -> registerService.expectedBreakdown(sessionId)).openBillCount())
        .isZero();
    assertThat(close(sessionId).status()).isEqualTo("CLOSED");
  }

  @Test
  void anotherTenantsOpenBillAtTheSameOutletIdIsInvisibleUnderRls() {
    // Same outlet UUID in two companies — RLS, not business_id, keeps the other tenant's bill out.
    UUID outlet = outlet();
    UUID sessionId = openSession(TENANT, outlet);
    openBill(OTHER_TENANT, outlet);

    assertThat(asTenant(() -> registerService.expectedBreakdown(sessionId)).openBillCount())
        .isZero();
    assertThat(close(sessionId).status()).isEqualTo("CLOSED");
  }
}
