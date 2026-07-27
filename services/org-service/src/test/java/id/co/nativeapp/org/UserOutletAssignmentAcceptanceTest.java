package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.dto.UserOutletAssignmentResponse;
import id.co.nativeapp.org.user.service.InvalidOutletAssignmentException;
import id.co.nativeapp.org.user.service.UserOutletAssignmentService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance tests for user ↔ outlet assignments (Phase 4).
 *
 * <p>Tests the PUT replace-set semantics, idempotency, row-close/reopen lifecycle, and validation
 * rules (type guard, active guard). Uses the real Postgres + RLS via {@link PostgresRlsTestBase}
 * (unprivileged {@code app_user}, {@code FORCE ROW LEVEL SECURITY}).
 *
 * <p><strong>No Keycloak needed here.</strong> The cross-tenant guard (Keycloak Admin lookup) is
 * exercised in the secured-profile acceptance test. These tests exercise the DB/RLS paths only by
 * injecting the service layer directly and binding {@link TenantContext} manually — same pattern as
 * {@link OrgUnitCrossTenantIsolationTest}.
 *
 * <p>Because {@link
 * id.co.nativeapp.org.user.service.UserOutletAssignmentService#listOutletsForUser} and {@link
 * id.co.nativeapp.org.user.service.UserOutletAssignmentService#replaceOutletsForUser} call through
 * to {@link id.co.nativeapp.org.user.service.KeycloakAdminClient} for the cross-tenant guard, this
 * test invokes the reader/writer directly via the service without the KC guard path — see {@link
 * UserOutletAssignmentService#listOutletsForMe()} for the "me" path and {@link
 * id.co.nativeapp.org.user.service.UserOutletAssignmentReader} for the raw read path used in
 * isolation tests.
 */
@SpringBootTest
class UserOutletAssignmentAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentService assignmentService;
  @Autowired private id.co.nativeapp.org.user.service.UserOutletAssignmentWriter assignmentWriter;
  @Autowired private id.co.nativeapp.org.user.service.UserOutletAssignmentReader assignmentReader;

  private static final String USER_A = "kc-sub-user-a";
  private static final String ACTOR = "owner";

  /** Bootstraps a company and returns (companyId, rootBusinessUnitId). */
  private record TenantSetup(UUID companyId, UUID rootId) {}

  private TenantSetup bootstrap(String name) {
    var r =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", ACTOR));
    return new TenantSetup(r.company().getId(), r.firstBusiness().getId());
  }

  /** Creates an OUTLET under the root business unit of the given tenant. */
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
  // Happy path: PUT assigns, GET reads back, re-PUT is idempotent
  // -------------------------------------------------------------------------

  @Test
  void putAssignsOutletsAndGetReturnsBoth() throws Exception {
    TenantSetup t = bootstrap("AcmeAssign");
    UUID outletX = createOutlet(t, "Outlet X");
    UUID outletY = createOutlet(t, "Outlet Y");

    // PUT with both outlets.
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX, outletY)));

    // GET returns both, ordered by name.
    List<UserOutletAssignmentResponse> result =
        TenantContext.callAs(
            t.companyId().toString(),
            ACTOR,
            () ->
                assignmentReader.findActiveByUserId(USER_A).stream()
                    .map(v -> new UserOutletAssignmentResponse(v.getOrgUnitId(), v.getName()))
                    .toList());

    assertThat(result).hasSize(2);
    assertThat(result.stream().map(UserOutletAssignmentResponse::orgUnitId).toList())
        .containsExactlyInAnyOrder(outletX, outletY);
  }

  @Test
  void replacingSetClosesRemovedOutlets() throws Exception {
    TenantSetup t = bootstrap("AcmeReplace");
    UUID outletX = createOutlet(t, "Outlet X");
    UUID outletY = createOutlet(t, "Outlet Y");

    // Assign both.
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX, outletY)));

    // Replace with only X — Y should be closed.
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX)));

    List<id.co.nativeapp.org.user.projection.UserOutletAssignmentView> active =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> assignmentReader.findActiveByUserId(USER_A));

    assertThat(active).hasSize(1);
    assertThat(active.get(0).getOrgUnitId()).isEqualTo(outletX);
  }

  @Test
  void rePutWithSameSetIsIdempotent() throws Exception {
    TenantSetup t = bootstrap("AcmeIdempotent");
    UUID outletX = createOutlet(t, "Outlet X");
    UUID outletY = createOutlet(t, "Outlet Y");

    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX, outletY)));

    // Second PUT with the same set — must be a no-op (no exception, same result).
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX, outletY)));

    List<id.co.nativeapp.org.user.projection.UserOutletAssignmentView> active =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> assignmentReader.findActiveByUserId(USER_A));

    assertThat(active).hasSize(2);
  }

  @Test
  void removedOutletThenReAddedIsReopened() throws Exception {
    TenantSetup t = bootstrap("AcmeReopen");
    UUID outletX = createOutlet(t, "Outlet X");

    // Assign X.
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX)));

    // Remove X (replace with empty set).
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of()));

    List<id.co.nativeapp.org.user.projection.UserOutletAssignmentView> afterRemove =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> assignmentReader.findActiveByUserId(USER_A));
    assertThat(afterRemove).isEmpty();

    // Re-add X — should reopen the existing row (no UNIQUE constraint violation).
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletX)));

    List<id.co.nativeapp.org.user.projection.UserOutletAssignmentView> afterReadd =
        TenantContext.callAs(
            t.companyId().toString(), ACTOR, () -> assignmentReader.findActiveByUserId(USER_A));
    assertThat(afterReadd).hasSize(1);
    assertThat(afterReadd.get(0).getOrgUnitId()).isEqualTo(outletX);
  }

  // -------------------------------------------------------------------------
  // Validation: assigning a non-OUTLET org unit returns 400
  // -------------------------------------------------------------------------

  @Test
  void assigningABusinessUnitReturns400() throws Exception {
    TenantSetup t = bootstrap("AcmeNonOutlet400");
    UUID businessUnitId = t.rootId();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(),
                    ACTOR,
                    () -> {
                      assignmentWriter.replaceAssignments(USER_A, List.of(businessUnitId));
                      return null;
                    }))
        .isInstanceOf(InvalidOutletAssignmentException.class)
        .hasMessageContaining(businessUnitId.toString());
  }

  @Test
  void assigningAnInactiveOutletReturns400() throws Exception {
    TenantSetup t = bootstrap("AcmeInactive400");
    UUID outletId = createOutlet(t, "Closed Outlet");

    // Deactivate the outlet.
    TenantContext.runAs(
        t.companyId().toString(),
        ACTOR,
        () ->
            orgUnitService.patch(
                new PatchOrgUnitCommand(outletId, null, false, null, true, false)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(),
                    ACTOR,
                    () -> {
                      assignmentWriter.replaceAssignments(USER_A, List.of(outletId));
                      return null;
                    }))
        .isInstanceOf(InvalidOutletAssignmentException.class)
        .hasMessageContaining("not active");
  }

  @Test
  void assigningAnUnknownOrgUnitIdReturns400() throws Exception {
    TenantSetup t = bootstrap("AcmeUnknown400");
    UUID unknownId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    t.companyId().toString(),
                    ACTOR,
                    () -> {
                      assignmentWriter.replaceAssignments(USER_A, List.of(unknownId));
                      return null;
                    }))
        .isInstanceOf(InvalidOutletAssignmentException.class)
        .hasMessageContaining(unknownId.toString());
  }

  // -------------------------------------------------------------------------
  // listOutletsForMe: reads caller's own assignments from TenantContext.actor()
  // -------------------------------------------------------------------------

  @Test
  void listOutletsForMeReturnsCallerOwnAssignments() throws Exception {
    TenantSetup t = bootstrap("AcmeMe");
    UUID outletId = createOutlet(t, "My Outlet");

    // Assign outletId to the "me" actor.
    TenantContext.runAs(
        t.companyId().toString(),
        USER_A, // actor IS the user
        () -> assignmentWriter.replaceAssignments(USER_A, List.of(outletId)));

    // listOutletsForMe reads from TenantContext.actor() == USER_A.
    List<UserOutletAssignmentResponse> result =
        TenantContext.callAs(
            t.companyId().toString(), USER_A, () -> assignmentService.listOutletsForMe());

    assertThat(result).hasSize(1);
    assertThat(result.get(0).orgUnitId()).isEqualTo(outletId);
    assertThat(result.get(0).name()).isEqualTo("My Outlet");
  }
}
