package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.catalog.domain.CatalogItemNotFoundException;
import id.co.nativeapp.barbershop.catalog.domain.StaffProfileWriteForbiddenException;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.config.ActorRolesProvider;
import id.co.nativeapp.barbershop.entitlement.domain.NotEntitledException;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Acceptance tests for the barbershop catalog feature (services, addons, staff profiles): CRUD
 * round trips, list filters, uniqueness, entitlement gating on writes, tenancy isolation on patch,
 * and the {@code durationMinutes} reserved column. Ported from carwash-service's {@code
 * CatalogAcceptanceTest} (ADR 0024).
 */
@SpringBootTest
class CatalogAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final String ACTOR_B = "attendant-b@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OUTLET_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void serviceCrudRoundTripsAndActiveOnlyFilterHidesADeactivatedRow() throws Exception {
    grantBarbershop(TENANT_A);

    CatalogItemResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Haircut", "classic cut", 30_000_00L, "IDR", 30)));
    assertThat(created.name()).isEqualTo("Haircut");
    assertThat(created.priceMinor()).isEqualTo(30_000_00L);
    assertThat(created.currency()).isEqualTo("IDR");
    assertThat(created.active()).isTrue();
    assertThat(created.durationMinutes()).isEqualTo(30);

    CatalogItemResponse patched =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.patchService(
                    created.id(),
                    new CatalogItemPatchRequest(
                        "Deluxe Haircut", null, 35_000_00L, false, 1, 45)));
    assertThat(patched.name()).isEqualTo("Deluxe Haircut");
    assertThat(patched.priceMinor()).isEqualTo(35_000_00L);
    assertThat(patched.active()).isFalse();
    assertThat(patched.displayOrder()).isEqualTo(1);
    assertThat(patched.durationMinutes()).isEqualTo(45);
    assertThat(patched.description()).isEqualTo("classic cut"); // unset field stays unchanged

    List<CatalogItemResponse> activeOnly =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listServices(OUTLET, true));
    assertThat(activeOnly).extracting(CatalogItemResponse::id).doesNotContain(created.id());

    List<CatalogItemResponse> everything =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listServices(OUTLET, false));
    assertThat(everything).extracting(CatalogItemResponse::id).contains(created.id());
  }

  @Test
  void addonCrudRoundTripsAndCarriesNoDurationMinutes() throws Exception {
    grantBarbershop(TENANT_A);

    CatalogItemResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createAddon(
                    new CatalogItemCreateRequest(
                        OUTLET, "Hot Towel", null, 15_000_00L, "IDR", null)));
    assertThat(created.name()).isEqualTo("Hot Towel");
    // service_addon has no duration_minutes column — always null, even if a create request set it.
    assertThat(created.durationMinutes()).isNull();

    CatalogItemResponse patched =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.patchAddon(
                    created.id(),
                    new CatalogItemPatchRequest(null, "warm towel service", null, null, null, null)));
    assertThat(patched.description()).isEqualTo("warm towel service");
    assertThat(patched.priceMinor()).isEqualTo(15_000_00L); // unset field stays unchanged
    assertThat(patched.durationMinutes()).isNull();

    List<CatalogItemResponse> addons =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listAddons(OUTLET, false));
    assertThat(addons).extracting(CatalogItemResponse::id).contains(created.id());
  }

  @Test
  void staffProfileCrudRoundTrips() throws Exception {
    grantBarbershop(TENANT_A);
    UUID employeeId = UUID.randomUUID();

    StaffProfileResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi", null, null)));
    assertThat(created.displayLabel()).isEqualTo("Budi");
    assertThat(created.employeeId()).isNull();
    assertThat(created.active()).isTrue(); // null "active" on create defaults to true

    StaffProfileResponse patched =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.patchStaffProfile(
                    created.id(), new StaffProfilePatchRequest("Budi S.", employeeId, false)));
    assertThat(patched.displayLabel()).isEqualTo("Budi S.");
    assertThat(patched.employeeId()).isEqualTo(employeeId);
    assertThat(patched.active()).isFalse();

    List<StaffProfileResponse> all =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> catalogService.listStaffProfiles(OUTLET, false));
    assertThat(all).extracting(StaffProfileResponse::id).contains(created.id());
  }

  @Test
  void businessIdFilterScopesResultsToOneOutlet() throws Exception {
    grantBarbershop(TENANT_A);

    CatalogItemResponse atOutlet1 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Outlet 1 Haircut", null, 10_000_00L, "IDR", null)));
    CatalogItemResponse atOutlet2 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET_2, "Outlet 2 Haircut", null, 10_000_00L, "IDR", null)));

    List<CatalogItemResponse> outlet1Only =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listServices(OUTLET, false));
    assertThat(outlet1Only)
        .extracting(CatalogItemResponse::id)
        .contains(atOutlet1.id())
        .doesNotContain(atOutlet2.id());
  }

  @Test
  void aDuplicateStaffDisplayLabelAtTheSameOutletIsRejected() throws Exception {
    grantBarbershop(TENANT_A);
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createStaffProfile(
                new StaffProfileCreateRequest(OUTLET, "Budi", null, true)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createStaffProfile(
                            new StaffProfileCreateRequest(OUTLET, "Budi", null, true))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void writesAreRejectedWhenTheCompanyIsNotEntitled() throws Exception {
    // No grant for this tenant.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createService(
                            new CatalogItemCreateRequest(OUTLET, "X", null, 1_000L, "IDR", null))))
        .isInstanceOf(NotEntitledException.class);
  }

  @Test
  void writesAreRejectedAfterTheEntitlementIsRevoked() throws Exception {
    grantBarbershop(TENANT_A);
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, BARBERSHOP, false));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createService(
                            new CatalogItemCreateRequest(OUTLET, "X", null, 1_000L, "IDR", null))))
        .isInstanceOf(NotEntitledException.class);
  }

  @Test
  void readsAreAllowedEvenWhenTheCompanyIsNotEntitled() throws Exception {
    // No grant at all — the list must not throw, just return whatever RLS allows (empty here).
    List<CatalogItemResponse> list =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listServices(null, true));
    assertThat(list).isEmpty();
  }

  @Test
  void patchOfACrossTenantServiceIsRejectedAsNotFound() throws Exception {
    grantBarbershop(TENANT_A);
    grantBarbershop(TENANT_B);

    CatalogItemResponse serviceA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "A Haircut", null, 10_000_00L, "IDR", null)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR_B,
                    () ->
                        catalogService.patchService(
                            serviceA.id(),
                            new CatalogItemPatchRequest(
                                "hijacked", null, null, null, null, null))))
        .isInstanceOf(CatalogItemNotFoundException.class);
  }

  @Test
  void aCashierRoleIsForbiddenFromStaffProfileWritesButAnOwnerIsNot() throws Exception {
    // Mirrors carwash review W3: staff profiles route commission (the employee link), so their
    // writes are held to owner/manager even though the gateway admits cashiers to
    // /api/v1/barbershop/**. Roles come from the X-Roles header the gateway stamps; install a mock
    // request to simulate each caller.
    grantBarbershop(TENANT_A);

    withRolesHeader(
        "cashier",
        () ->
            assertThatThrownBy(
                    () ->
                        TenantContext.callAs(
                            TENANT_A,
                            ACTOR_A,
                            () ->
                                catalogService.createStaffProfile(
                                    new StaffProfileCreateRequest(OUTLET, "Budi", null, null))))
                .isInstanceOf(StaffProfileWriteForbiddenException.class));

    StaffProfileResponse created =
        withRolesHeader(
            "owner",
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createStaffProfile(
                            new StaffProfileCreateRequest(OUTLET, "Budi", null, null))));
    assertThat(created.displayLabel()).isEqualTo("Budi");

    // A cashier also cannot RELINK an existing profile (the commission-redirect vector).
    withRolesHeader(
        "cashier",
        () ->
            assertThatThrownBy(
                    () ->
                        TenantContext.callAs(
                            TENANT_A,
                            ACTOR_A,
                            () ->
                                catalogService.patchStaffProfile(
                                    created.id(),
                                    new StaffProfilePatchRequest(null, UUID.randomUUID(), null))))
                .isInstanceOf(StaffProfileWriteForbiddenException.class));

    // But service writes stay at restaurant-menu parity: a cashier CAN create a service.
    CatalogItemResponse service =
        withRolesHeader(
            "cashier",
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createService(
                            new CatalogItemCreateRequest(
                                OUTLET, "Cashier Haircut", null, 5_000_00L, "IDR", null))));
    assertThat(service.name()).isEqualTo("Cashier Haircut");
  }

  /** Runs the body with a mock HTTP request carrying the given X-Roles header, then cleans up. */
  private static <T> T withRolesHeader(String roles, java.util.concurrent.Callable<T> body)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorRolesProvider.ROLES_HEADER, roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      return body.call();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static void withRolesHeader(String roles, Runnable body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorRolesProvider.ROLES_HEADER, roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      body.run();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, BARBERSHOP, true));
  }
}
