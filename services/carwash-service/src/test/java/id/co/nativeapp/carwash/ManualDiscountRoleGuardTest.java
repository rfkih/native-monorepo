package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.service.TicketService;
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
 * requires {@code owner}/{@code manager} at ticket checkout — a cashier/attendant is rejected with
 * {@link ManualDiscountForbiddenException} (403 via {@code config.PromotionAdvice}); owner/manager
 * and a headerless (dev-recipe / direct test) caller both pass. Ported from restaurant-service's
 * equivalent suite, TRIMMED to carwash's single checkout write path (no park/pay-parked/pay-bill
 * flows exist here — ADR 0023 decision 1).
 *
 * <p>Mirrors {@link TicketOutletAccessTest}'s {@code setRoles} idiom: a {@link
 * MockHttpServletRequest} is bound directly to {@link RequestContextHolder} so {@link
 * id.co.nativeapp.carwash.config.ActorRolesProvider} reads the simulated {@code X-Roles} gateway
 * header — no MockMvc, no HTTP server.
 */
@SpringBootTest
class ManualDiscountRoleGuardTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666666";
  private static final String ACTOR = "actor@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666601");
  private static final String CARWASH = "carwash";

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

  private void grantCarwash() {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, CARWASH, true));
  }

  private UUID createPackage() throws Exception {
    return TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Basic Wash", null, 20_000_00L, "IDR")))
        .id();
  }

  private static CheckoutRequest checkoutRequest(UUID packageId, String idempotencyKey, Long discountMinor) {
    return new CheckoutRequest(
        OUTLET,
        idempotencyKey,
        "bay-1",
        null,
        null,
        discountMinor,
        List.of(new TicketLineInput(ItemType.PACKAGE, packageId, 1)),
        new PaymentRequest(TenderType.CASH, 100_000_00L));
  }

  @Test
  void cashierWithAPositiveManualDiscountIsRejectedAtCheckout() throws Exception {
    grantCarwash();
    UUID packageId = createPackage();
    setRoles("cashier");

    CheckoutRequest req = checkoutRequest(packageId, "manual-discount-1", 5_000_00L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req)))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void ownerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    grantCarwash();
    UUID packageId = createPackage();
    setRoles("owner");

    CheckoutRequest req = checkoutRequest(packageId, "manual-discount-2", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
    // 20,000.00 - 5,000.00 discount, no tax/SC seeded for this fresh tenant.
    assertThat(result.ticket().breakdown().grandTotalMinor()).isEqualTo(15_000_00L);
    assertThat(result.ticket().breakdown().discountMinor()).isEqualTo(5_000_00L);
  }

  @Test
  void managerWithAPositiveManualDiscountSucceedsAtCheckout() throws Exception {
    grantCarwash();
    UUID packageId = createPackage();
    setRoles("manager");

    CheckoutRequest req = checkoutRequest(packageId, "manual-discount-3", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void headerlessCallerWithAPositiveManualDiscountSucceedsAtCheckoutDevRecipeTrust() throws Exception {
    grantCarwash();
    UUID packageId = createPackage();
    clearRoles(); // no X-Roles header at all — empty-roles-pass

    CheckoutRequest req = checkoutRequest(packageId, "manual-discount-4", 5_000_00L);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }

  @Test
  void cashierWithNoDiscountIsNotBlocked() throws Exception {
    grantCarwash();
    UUID packageId = createPackage();
    setRoles("cashier");

    CheckoutRequest req = checkoutRequest(packageId, "manual-discount-5", null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(req));
    assertThat(result.created()).isTrue();
  }
}
