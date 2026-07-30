package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance — the tenancy isolation proof for the ticket feature, relying on AUTO-applied RLS
 * (mirrors {@code WashTenancyIsolationTest}). A ticket (and its catalog rows) recorded under tenant A
 * is invisible to tenant B: {@code GET /tickets/{id}} 404s (via {@link TicketNotFoundException}) and
 * tenant B's catalog listing never includes tenant A's package.
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
    grantCarwash(TENANT_A);
    grantCarwash(TENANT_B);

    CatalogItemResponse packageA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Tenant A Wash", null, 30_000_00L, "IDR")));

    TicketResponse ticketA =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () ->
                    ticketService.checkout(
                        new CheckoutRequest(
                            OUTLET,
                            "tenancy-a-1",
                            "bay-1",
                            null,
                            null,
                            null,
                            List.of(new TicketLineInput(ItemType.PACKAGE, packageA.id(), 1)),
                            new PaymentRequest(TenderType.CASH, 100_000_00L))))
            .ticket();

    // Tenant B: a fresh catalog + ticket of its own, then prove neither tenant A row is visible.
    CatalogItemResponse packageB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Tenant B Wash", null, 20_000_00L, "IDR")));

    TicketResponse ticketB =
        TenantContext.callAs(
                TENANT_B,
                ACTOR_B,
                () ->
                    ticketService.checkout(
                        new CheckoutRequest(
                            OUTLET,
                            "tenancy-b-1",
                            "bay-1",
                            null,
                            null,
                            null,
                            List.of(new TicketLineInput(ItemType.PACKAGE, packageB.id(), 1)),
                            new PaymentRequest(TenderType.CASH, 100_000_00L))))
            .ticket();

    // GET: tenant A's ticket is invisible from tenant B's scope (404), and vice versa.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR_B, () -> ticketService.getById(ticketA.ticketId())))
        .isInstanceOf(TicketNotFoundException.class);
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.getById(ticketB.ticketId())))
        .isInstanceOf(TicketNotFoundException.class);

    // GET: each tenant sees its own ticket fine.
    TicketResponse aVisible =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.getById(ticketA.ticketId()));
    assertThat(aVisible.ticketId()).isEqualTo(ticketA.ticketId());

    // Catalog rows: tenant B's package listing never includes tenant A's package.
    List<UUID> bVisiblePackageIds =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                catalogService.listPackages(null, false).stream()
                    .map(CatalogItemResponse::id)
                    .toList());
    assertThat(bVisiblePackageIds).contains(packageB.id()).doesNotContain(packageA.id());
  }

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, "carwash", true));
  }
}
