package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.config.ActorTypeProvider;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.barbershop.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.barbershop.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.domain.OperatorRequiredException;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.security.OperatorPrincipal;
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
 * Outlet-scoping enforcement (Phase 5 policy) integrated into ticket checkout — the {@code
 * OutletAccessGuardTest} fixtures/idioms applied end-to-end through {@link TicketService#checkout}.
 * Ported from carwash-service's {@code TicketOutletAccessTest} (ADR 0024).
 *
 * <ol>
 *   <li>A cashier with NO active assignment at an outlet the company HAS adopted scoping for → 403
 *       ({@link OutletNotAssignedException}).
 *   <li>Grandfather: the company has ZERO assignment rows (scoping never adopted) → allowed.
 *   <li>Owner bypass: an owner role checks out even when scoping is adopted and they have no
 *       assignment row.
 * </ol>
 */
@SpringBootTest
class TicketOutletAccessTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OWNER_ACTOR = "owner@example.co.id";
  private static final String CASHIER_ACTOR = "attendant-01@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private UserOutletAssignmentRefService assignmentService;

  @BeforeEach
  void bindMockRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  /**
   * Rebinds a FRESH request carrying the given {@code X-Roles} header — {@link
   * MockHttpServletRequest} headers are additive, so switching roles mid-test requires a new
   * request bound to the holder (the {@code OutletAccessGuardTest} idiom).
   */
  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  /**
   * ADR 0049 P4: binds a request carrying both {@code X-Roles} and {@code X-Actor-Type: device},
   * plus (optionally) a pre-verified {@link OperatorPrincipal} request attribute — mirroring what
   * {@code OperatorSessionFilter} sets after verifying a real {@code X-Operator-Session} token.
   */
  private void setRolesAsDeviceActor(String roles, OperatorPrincipal operator) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    request.addHeader(ActorTypeProvider.ACTOR_TYPE_HEADER, ActorTypeProvider.DEVICE);
    if (operator != null) {
      request.setAttribute(OperatorPrincipal.REQUEST_ATTRIBUTE, operator);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void cashierWithoutAssignmentIsRejectedWith403WhenScopingIsAdopted() throws Exception {
    grantBarbershop(TENANT);
    // Seed a row for a DIFFERENT user so the company has adopted scoping (no grandfather).
    assignTo("other-attendant", UUID.randomUUID(), OUTLET);
    CatalogItemResponse service = createService();
    StaffProfileResponse barber = createStaffProfile();

    setRoles("cashier");
    CheckoutRequest request = checkoutRequest(service.id(), barber.id(), "outlet-403-1");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> ticketService.checkout(request)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  @Test
  void cashierIsAllowedWhenTheCompanyHasZeroAssignmentRowsGrandfather() throws Exception {
    grantBarbershop(TENANT);
    // user_outlet_assignment_ref is empty (truncated in resetTables()) — grandfather applies.
    CatalogItemResponse service = createService();
    StaffProfileResponse barber = createStaffProfile();

    setRoles("cashier");
    CheckoutRequest request = checkoutRequest(service.id(), barber.id(), "outlet-grandfather-1");

    CheckoutResult result =
        TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> ticketService.checkout(request));
    assertThat(result.created()).isTrue();
  }

  @Test
  void ownerBypassesTheOutletCheckEvenWhenScopingIsAdopted() throws Exception {
    grantBarbershop(TENANT);
    assignTo("other-attendant", UUID.randomUUID(), OUTLET);
    CatalogItemResponse service = createService();
    StaffProfileResponse barber = createStaffProfile();

    setRoles("owner");
    CheckoutRequest request = checkoutRequest(service.id(), barber.id(), "outlet-owner-1");

    CheckoutResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> ticketService.checkout(request));
    assertThat(result.created()).isTrue();
  }

  // ---------------------------------------------------------------- ADR 0049 P4: device guard

  @Test
  void deviceActorWithNoOperatorSessionIsRejectedWithOperatorRequired() throws Exception {
    grantBarbershop(TENANT);
    CatalogItemResponse service = createService();
    StaffProfileResponse barber = createStaffProfile();

    setRolesAsDeviceActor("cashier", null);
    CheckoutRequest request = checkoutRequest(service.id(), barber.id(), "device-no-operator-1");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> ticketService.checkout(request)))
        .isInstanceOf(OperatorRequiredException.class);
  }

  @Test
  void deviceActorWithAVerifiedOperatorSessionIsAdmitted() throws Exception {
    grantBarbershop(TENANT);
    CatalogItemResponse service = createService();
    StaffProfileResponse barber = createStaffProfile();

    OperatorPrincipal operator =
        new OperatorPrincipal(
            TENANT, OUTLET, UUID.randomUUID().toString(), UUID.randomUUID(), "cashier");
    setRolesAsDeviceActor("cashier", operator);
    CheckoutRequest request = checkoutRequest(service.id(), barber.id(), "device-operator-1");

    CheckoutResult result =
        TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> ticketService.checkout(request));
    assertThat(result.created()).isTrue();
  }

  private CatalogItemResponse createService() throws Exception {
    return TenantContext.callAs(
        TENANT,
        OWNER_ACTOR,
        () ->
            catalogService.createService(
                new CatalogItemCreateRequest(OUTLET, "Haircut", null, 30_000_00L, "IDR", null)));
  }

  private StaffProfileResponse createStaffProfile() throws Exception {
    return TenantContext.callAs(
        TENANT,
        OWNER_ACTOR,
        () ->
            catalogService.createStaffProfile(
                new StaffProfileCreateRequest(OUTLET, "Budi", null, true)));
  }

  private static CheckoutRequest checkoutRequest(
      UUID serviceId, UUID staffProfileId, String idempotencyKey) {
    return new CheckoutRequest(
        OUTLET,
        idempotencyKey,
        "chair-1",
        staffProfileId,
        null,
        List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
        new PaymentRequest(TenderType.CASH, 100_000_00L));
  }

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, "barbershop", true));
  }

  private void assignTo(String userId, UUID assignmentId, UUID outletId) {
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(),
            assignmentId,
            userId,
            TENANT,
            outletId,
            "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);
  }
}
