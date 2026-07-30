package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
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
 * requires {@code owner}/{@code manager} at ticket checkout — a cashier/barber is rejected with
 * {@link ManualDiscountForbiddenException} (403 via {@code config.PromotionAdvice}); owner/manager
 * and a headerless (dev-recipe / direct test) caller both pass. Ported from carwash-service's
 * identical port of restaurant-service's equivalent suite, TRIMMED to barbershop's single checkout
 * write path (no park/pay-parked/pay-bill flows exist here — ADR 0023 decision 1, preserved by ADR
 * 0024). Unlike carwash, {@code staffProfileId} is MANDATORY (every cut has a barber, ADR 0024) — a
 * staff profile is created for every checkout in this suite.
 *
 * <p>Mirrors {@link TicketOutletAccessTest}'s {@code setRoles} idiom: a {@link
 * MockHttpServletRequest} is bound directly to {@link RequestContextHolder} so {@link
 * id.co.nativeapp.barbershop.config.ActorRolesProvider} reads the simulated {@code X-Roles} gateway
 * header — no MockMvc, no HTTP server.
 */
@SpringBootTest
class ManualDiscountRoleGuardTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666666";
  private static final String ACTOR = "actor@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666601");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @BeforeEach
  void bindMockRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void clearRoles() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void grantBarbershop() {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, BARBERSHOP, true));
  }

  private UUID createService() throws Exception {
    return TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(OUTLET, "Haircut", null, 20_000_00L, "IDR", null)))
        .id();
  }

  private UUID createBarber() throws Exception {
    return TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi", null, true)))
        .id();
  }

  private static CheckoutRequest checkoutRequest(
      UUID serviceId, UUID staffProfileId, String idempotencyKey, Long discountMinor) {
    return new CheckoutRequest(
        OUTLET,
        idempotencyKey,
        "chair-1",
        staffProfileId,
        discountMinor,
        List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1)),
        new PaymentRequest(TenderType.CASH, 100_000_00L));
  }

  @Test
  void cashierWithAPositiveManualDiscountIsRejectedAtCheckout() throws Exception {
    grantBarbershop();
    UUID serviceId = createService();
    UUID barberId = createBarber();
    setRoles("cashier");

    CheckoutRequest req = checkoutRequest(serviceId, barberId, "manual-discount-1", 5_000_00L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req)))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void ownerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    grantBarbershop();
    UUID serviceId = createService();
    UUID barberId = createBarber();
    setRoles("owner");

    CheckoutRequest req = checkoutRequest(serviceId, barberId, "manual-discount-2", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
    // 20,000.00 - 5,000.00 discount, no tax/SC seeded for this fresh tenant.
    assertThat(result.ticket().breakdown().grandTotalMinor()).isEqualTo(15_000_00L);
    assertThat(result.ticket().breakdown().discountMinor()).isEqualTo(5_000_00L);
  }

  @Test
  void managerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    grantBarbershop();
    UUID serviceId = createService();
    UUID barberId = createBarber();
    setRoles("manager");

    CheckoutRequest req = checkoutRequest(serviceId, barberId, "manual-discount-3", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void headerlessCallerWithAPositiveManualDiscountSucceedsAtCheckoutDevRecipeTrust() throws Exception {
    grantBarbershop();
    UUID serviceId = createService();
    UUID barberId = createBarber();
    clearRoles(); // no X-Roles header at all — empty-roles-pass

    CheckoutRequest req = checkoutRequest(serviceId, barberId, "manual-discount-4", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void cashierWithNoDiscountIsNotBlocked() throws Exception {
    grantBarbershop();
    UUID serviceId = createService();
    UUID barberId = createBarber();
    setRoles("cashier");

    CheckoutRequest req = checkoutRequest(serviceId, barberId, "manual-discount-5", null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }
}
