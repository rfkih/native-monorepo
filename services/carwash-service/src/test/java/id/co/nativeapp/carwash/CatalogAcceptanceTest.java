package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.catalog.domain.CatalogItemNotFoundException;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Acceptance tests for the carwash catalog feature (packages, addons, staff profiles): CRUD round
 * trips, list filters, uniqueness, entitlement gating on writes, and tenancy isolation on patch.
 */
@SpringBootTest
class CatalogAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final String ACTOR_B = "attendant-b@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OUTLET_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String CARWASH = "carwash";

  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void packageCrudRoundTripsAndActiveOnlyFilterHidesADeactivatedRow() throws Exception {
    grantCarwash(TENANT_A);

    CatalogItemResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET, "Basic Wash", "soap + rinse", 30_000_00L, "IDR")));
    assertThat(created.name()).isEqualTo("Basic Wash");
    assertThat(created.priceMinor()).isEqualTo(30_000_00L);
    assertThat(created.currency()).isEqualTo("IDR");
    assertThat(created.active()).isTrue();

    CatalogItemResponse patched =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.patchPackage(
                    created.id(),
                    new CatalogItemPatchRequest("Deluxe Wash", null, 35_000_00L, false, 1)));
    assertThat(patched.name()).isEqualTo("Deluxe Wash");
    assertThat(patched.priceMinor()).isEqualTo(35_000_00L);
    assertThat(patched.active()).isFalse();
    assertThat(patched.displayOrder()).isEqualTo(1);
    assertThat(patched.description()).isEqualTo("soap + rinse"); // unset field stays unchanged

    List<CatalogItemResponse> activeOnly =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listPackages(OUTLET, true));
    assertThat(activeOnly).extracting(CatalogItemResponse::id).doesNotContain(created.id());

    List<CatalogItemResponse> everything =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listPackages(OUTLET, false));
    assertThat(everything).extracting(CatalogItemResponse::id).contains(created.id());
  }

  @Test
  void addonCrudRoundTrips() throws Exception {
    grantCarwash(TENANT_A);

    CatalogItemResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> catalogService.createAddon(new CatalogItemCreateRequest(OUTLET, "Wax", null, 15_000_00L, "IDR")));
    assertThat(created.name()).isEqualTo("Wax");

    CatalogItemResponse patched =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.patchAddon(
                    created.id(), new CatalogItemPatchRequest(null, "carnauba wax", null, null, null)));
    assertThat(patched.description()).isEqualTo("carnauba wax");
    assertThat(patched.priceMinor()).isEqualTo(15_000_00L); // unset field stays unchanged

    List<CatalogItemResponse> addons =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listAddons(OUTLET, false));
    assertThat(addons).extracting(CatalogItemResponse::id).contains(created.id());
  }

  @Test
  void staffProfileCrudRoundTrips() throws Exception {
    grantCarwash(TENANT_A);
    UUID employeeId = UUID.randomUUID();

    StaffProfileResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> catalogService.createStaffProfile(new StaffProfileCreateRequest(OUTLET, "Budi", null, null)));
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
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listStaffProfiles(OUTLET, false));
    assertThat(all).extracting(StaffProfileResponse::id).contains(created.id());
  }

  @Test
  void businessIdFilterScopesResultsToOneOutlet() throws Exception {
    grantCarwash(TENANT_A);

    CatalogItemResponse atOutlet1 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> catalogService.createPackage(new CatalogItemCreateRequest(OUTLET, "Outlet 1 Wash", null, 10_000_00L, "IDR")));
    CatalogItemResponse atOutlet2 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createPackage(
                    new CatalogItemCreateRequest(OUTLET_2, "Outlet 2 Wash", null, 10_000_00L, "IDR")));

    List<CatalogItemResponse> outlet1Only =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listPackages(OUTLET, false));
    assertThat(outlet1Only)
        .extracting(CatalogItemResponse::id)
        .contains(atOutlet1.id())
        .doesNotContain(atOutlet2.id());
  }

  @Test
  void aDuplicateStaffDisplayLabelAtTheSameOutletIsRejected() throws Exception {
    grantCarwash(TENANT_A);
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> catalogService.createStaffProfile(new StaffProfileCreateRequest(OUTLET, "Budi", null, true)));

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
                        catalogService.createPackage(
                            new CatalogItemCreateRequest(OUTLET, "X", null, 1_000L, "IDR"))))
        .isInstanceOf(NotEntitledException.class);
  }

  @Test
  void writesAreRejectedAfterTheEntitlementIsRevoked() throws Exception {
    grantCarwash(TENANT_A);
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, CARWASH, false));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        catalogService.createPackage(
                            new CatalogItemCreateRequest(OUTLET, "X", null, 1_000L, "IDR"))))
        .isInstanceOf(NotEntitledException.class);
  }

  @Test
  void readsAreAllowedEvenWhenTheCompanyIsNotEntitled() throws Exception {
    // No grant at all — the list must not throw, just return whatever RLS allows (empty here).
    List<CatalogItemResponse> list =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> catalogService.listPackages(null, true));
    assertThat(list).isEmpty();
  }

  @Test
  void patchOfACrossTenantPackageIsRejectedAsNotFound() throws Exception {
    grantCarwash(TENANT_A);
    grantCarwash(TENANT_B);

    CatalogItemResponse packageA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> catalogService.createPackage(new CatalogItemCreateRequest(OUTLET, "A Wash", null, 10_000_00L, "IDR")));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR_B,
                    () ->
                        catalogService.patchPackage(
                            packageA.id(), new CatalogItemPatchRequest("hijacked", null, null, null, null))))
        .isInstanceOf(CatalogItemNotFoundException.class);
  }

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, CARWASH, true));
  }
}
