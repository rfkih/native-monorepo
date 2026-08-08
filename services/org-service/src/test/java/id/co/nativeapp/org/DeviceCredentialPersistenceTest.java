package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialAlreadyExistsException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialNotFoundException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialReader;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialTargetNotOutletException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialWriter;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Persistence + RLS acceptance tests for the {@code device_credential} table (ADR 0049 P3a),
 * exercising the real Postgres + RLS via {@link PostgresRlsTestBase} (unprivileged {@code
 * app_user}, {@code FORCE ROW LEVEL SECURITY}) — mirrors {@link
 * UserOutletAssignmentAcceptanceTest}.
 *
 * <p><strong>No Keycloak needed here.</strong> {@link DeviceCredentialWriter}/{@link
 * DeviceCredentialReader} are exercised directly (no {@code KeycloakAdminClient} call on this
 * path), proving the DB-layer invariants: PII round-trip (the password decrypts back to the
 * original plaintext), the {@code UNIQUE(company_id, org_unit_id)} guard, the outlet-type/
 * existence validation, and cross-tenant invisibility under RLS. The full mint→bind→reveal→reset→
 * delete flow (which DOES call Keycloak) is covered by the {@code secured}-profile acceptance test.
 */
@SpringBootTest
class DeviceCredentialPersistenceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private DeviceCredentialReader reader;
  @Autowired private DeviceCredentialWriter writer;

  private static final String ACTOR = "owner";

  private record TenantSetup(UUID companyId, UUID rootId) {}

  private TenantSetup bootstrap(String name) {
    var r =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", "restaurant", ACTOR));
    return new TenantSetup(r.company().getId(), r.firstBusiness().getId());
  }

  private UUID createOutlet(TenantSetup t, String outletName) throws Exception {
    return TenantContext.callAs(
        t.companyId().toString(),
        ACTOR,
        () -> {
          OrgUnit outlet =
              orgUnitService.create(new CreateOrgUnitCommand(outletName, "outlet", t.rootId()));
          return outlet.getId();
        });
  }

  // -------------------------------------------------------------------------
  // Happy path: persist, reveal (decrypted round trip), rotate, delete
  // -------------------------------------------------------------------------

  @Test
  void persistThenRequireCredentialRoundTripsTheDecryptedPassword() throws Exception {
    TenantSetup t = bootstrap("AcmeDevicePersist");
    UUID outletId = createOutlet(t, "Outlet 1");

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () ->
            writer.persist(
                outletId, "kc-sub-device-1", "till." + outletId, "TopSecretDevicePassword!"));

    DeviceCredential credential =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> reader.requireCredential(outletId));

    assertThat(credential.getOrgUnitId()).isEqualTo(outletId);
    assertThat(credential.getKeycloakUserId()).isEqualTo("kc-sub-device-1");
    assertThat(credential.getUsername()).isEqualTo("till." + outletId);
    // The stored/decrypted password round-trips exactly (PiiAttributeConverter, AES-256-GCM).
    assertThat(credential.getPassword()).isEqualTo("TopSecretDevicePassword!");
  }

  @Test
  void existsForOutletReflectsPersistState() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceExists");
    UUID outletId = createOutlet(t, "Outlet 1");

    boolean beforeCreate =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> reader.existsForOutlet(outletId));
    assertThat(beforeCreate).isFalse();

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> writer.persist(outletId, "kc-sub-device-1", "till." + outletId, "Pass1234!"));

    boolean afterCreate =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> reader.existsForOutlet(outletId));
    assertThat(afterCreate).isTrue();
  }

  @Test
  void aSecondPersistForTheSameOutletIsRejectedByTheUniqueConstraint() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceDuplicate");
    UUID outletId = createOutlet(t, "Outlet 1");

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> writer.persist(outletId, "kc-sub-device-1", "till." + outletId, "Pass1234!"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(),
                    ACTOR,
                    () ->
                        writer.persist(
                            outletId, "kc-sub-device-2", "till." + outletId, "OtherPass1!")))
        .isInstanceOf(DeviceCredentialAlreadyExistsException.class);
  }

  @Test
  void rotatePasswordChangesOnlyThePassword() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceRotate");
    UUID outletId = createOutlet(t, "Outlet 1");

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> writer.persist(outletId, "kc-sub-device-1", "till." + outletId, "OldPass1!"));

    TenantContext.runAs(
        t.companyId().toString(), ACTOR, () -> writer.rotatePassword(outletId, "NewPass2!"));

    DeviceCredential credential =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> reader.requireCredential(outletId));
    assertThat(credential.getPassword()).isEqualTo("NewPass2!");
    assertThat(credential.getKeycloakUserId()).isEqualTo("kc-sub-device-1");
  }

  @Test
  void deleteRemovesTheRowAndSubsequentReadThrows404() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceDelete");
    UUID outletId = createOutlet(t, "Outlet 1");

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> writer.persist(outletId, "kc-sub-device-1", "till." + outletId, "Pass1234!"));
    TenantContext.runAs(t.companyId().toString(), ACTOR, () -> writer.delete(outletId));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(), ACTOR, () -> reader.requireCredential(outletId)))
        .isInstanceOf(DeviceCredentialNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // requireOutlet validation
  // -------------------------------------------------------------------------

  @Test
  void requireOutletRejectsABusinessUnitTarget() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceNonOutlet");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(), ACTOR, () -> reader.requireOutlet(t.rootId())))
        .isInstanceOf(DeviceCredentialTargetNotOutletException.class)
        .hasMessageContaining("BUSINESS_UNIT");
  }

  @Test
  void requireOutletRejectsAnUnknownId() throws Exception {
    TenantSetup t = bootstrap("AcmeDeviceUnknownOutlet");
    UUID unknownId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(), ACTOR, () -> reader.requireOutlet(unknownId)))
        .isInstanceOf(OrgUnitNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Cross-tenant isolation (rule 5)
  // -------------------------------------------------------------------------

  @Test
  void aCredentialIsInvisibleFromAnotherTenantsScope() throws Exception {
    TenantSetup companyA = bootstrap("AcmeDeviceTenantA");
    TenantSetup companyB = bootstrap("AcmeDeviceTenantB");
    UUID outletA = createOutlet(companyA, "Outlet A");

    TenantContext.runAs(
        companyA.companyId().toString(),
        ACTOR,
        () -> writer.persist(outletA, "kc-sub-device-1", "till." + outletA, "Pass1234!"));

    // Scoped as company B, the same org-unit id is invisible (RLS fails closed) — the outlet
    // lookup itself 404s before the credential is ever consulted.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyB.companyId().toString(), ACTOR, () -> reader.requireOutlet(outletA)))
        .isInstanceOf(OrgUnitNotFoundException.class);

    boolean existsFromB =
        TenantContext.callAs(
            companyB.companyId().toString(), ACTOR, () -> reader.existsForOutlet(outletA));
    assertThat(existsFromB).isFalse();
  }
}
