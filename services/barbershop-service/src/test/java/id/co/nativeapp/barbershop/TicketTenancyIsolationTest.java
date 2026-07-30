package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance — the tenancy isolation proof for the ticket feature, relying on AUTO-applied RLS
 * (ported from carwash-service's {@code TicketTenancyIsolationTest}, ADR 0024). A ticket (and its
 * catalog rows) recorded under tenant A is invisible to tenant B: {@code GET /tickets/{id}} 404s
 * (via {@link TicketNotFoundException}) and tenant B's catalog listing never includes tenant A's
 * service.
 */
@SpringBootTest
class TicketTenancyIsolationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "attendant-a";
  private static final String ACTOR_B = "attendant-b";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void aTicketAndItsCatalogRowsRecordedUnderTenantAAreInvisibleToTenantB() throws Exception {
    grantBarbershop(TENANT_A);
    grantBarbershop(TENANT_B);

    CatalogItemResponse serviceA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Tenant A Haircut", null, 30_000_00L, "IDR", null)));
    StaffProfileResponse barberA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Tenant A Barber", null, true)));

    TicketResponse ticketA =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () ->
                    ticketService.checkout(
                        new CheckoutRequest(
                            OUTLET,
                            "tenancy-a-1",
                            "chair-1",
                            barberA.id(),
                            null,
                            List.of(new TicketLineInput(ItemType.SERVICE, serviceA.id(), 1)),
                            new PaymentRequest(TenderType.CASH, 100_000_00L))))
            .ticket();

    // Tenant B: a fresh catalog + ticket of its own, then prove neither tenant A row is visible.
    CatalogItemResponse serviceB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Tenant B Haircut", null, 20_000_00L, "IDR", null)));
    StaffProfileResponse barberB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Tenant B Barber", null, true)));

    TicketResponse ticketB =
        TenantContext.callAs(
                TENANT_B,
                ACTOR_B,
                () ->
                    ticketService.checkout(
                        new CheckoutRequest(
                            OUTLET,
                            "tenancy-b-1",
                            "chair-1",
                            barberB.id(),
                            null,
                            List.of(new TicketLineInput(ItemType.SERVICE, serviceB.id(), 1)),
                            new PaymentRequest(TenderType.CASH, 100_000_00L))))
            .ticket();

    // GET: tenant A's ticket is invisible from tenant B's scope (404), and vice versa.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> ticketService.getById(ticketA.ticketId())))
        .isInstanceOf(TicketNotFoundException.class);
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR_A, () -> ticketService.getById(ticketB.ticketId())))
        .isInstanceOf(TicketNotFoundException.class);

    // GET: each tenant sees its own ticket fine.
    TicketResponse aVisible =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.getById(ticketA.ticketId()));
    assertThat(aVisible.ticketId()).isEqualTo(ticketA.ticketId());

    // Catalog rows: tenant B's service listing never includes tenant A's service.
    List<UUID> bVisibleServiceIds =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                catalogService.listServices(null, false).stream()
                    .map(CatalogItemResponse::id)
                    .toList());
    assertThat(bVisibleServiceIds).contains(serviceB.id()).doesNotContain(serviceA.id());
  }

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, "barbershop", true));
  }
}
