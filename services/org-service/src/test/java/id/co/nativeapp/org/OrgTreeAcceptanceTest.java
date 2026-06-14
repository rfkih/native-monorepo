package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.CompanyService;
import id.co.nativeapp.org.company.CreateCompanyCommand;
import id.co.nativeapp.org.company.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.OrgUnit;
import id.co.nativeapp.org.company.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.OrgUnitService;
import id.co.nativeapp.org.company.PatchOrgUnitCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance (a/b/c) for the full org tree, end to end through {@link OrgUnitService} over a real
 * RLS-enforcing PostgreSQL (the unprivileged {@code app_user}; see {@link PostgresRlsTestBase}).
 *
 * <ul>
 *   <li><strong>(a)</strong> a nested {@code business_unit > branch > outlet > team} tree persists
 *       under the right parent + company, each node emitting exactly one {@code OrgUnitCreated}
 *       with the right type/parent_id;
 *   <li><strong>(b)</strong> an illegal parent→child type, an unknown/cross-tenant parent, and a
 *       cycle are each rejected with an {@code IllegalArgumentException} (→ 400 ProblemDetail) and
 *       write nothing;
 *   <li><strong>(c)</strong> renaming and moving a node each emit one {@code OrgUnitChanged}.
 * </ul>
 */
@SpringBootTest
class OrgTreeAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Bootstraps a company and returns {tenantId, rootBusinessUnitId}. */
  private record Tenant(UUID companyId, UUID rootBusinessUnitId) {}

  private Tenant bootstrapCompany(String name, String actor) {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", "outlet", actor));
    return new Tenant(result.company().getId(), result.firstBusiness().getId());
  }

  @Test
  void aNestedTreePersistsUnderTheRightParentAndEmitsOneOrgUnitCreatedPerNode() throws Exception {
    Tenant t = bootstrapCompany("Alpha", "owner-a");

    // Build branch > outlet > team under the bootstrap root business unit, in A's scope.
    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-a",
            () -> {
              OrgUnit branch =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("North Branch", "branch", t.rootBusinessUnitId()));
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet 1", "outlet", branch.getId()));
              OrgUnit team =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Kitchen Team", "team", outlet.getId()));
              return new UUID[] {branch.getId(), outlet.getId(), team.getId()};
            });
    UUID branchId = ids[0];
    UUID outletId = ids[1];
    UUID teamId = ids[2];

    // Each node persisted under the right parent (read over the admin connection).
    assertThat(parentOf(branchId)).isEqualTo(t.rootBusinessUnitId());
    assertThat(parentOf(outletId)).isEqualTo(branchId);
    assertThat(parentOf(teamId)).isEqualTo(outletId);
    // And under the right company.
    assertThat(companyOf(branchId)).isEqualTo(t.companyId().toString());
    assertThat(companyOf(teamId)).isEqualTo(t.companyId().toString());

    // Exactly one OrgUnitCreated per node — the 3 new ones + the bootstrap root = 4 total.
    List<Map<String, Object>> created = orgUnitCreatedRows();
    assertThat(created).hasSize(4);

    // The branch event carries type=BRANCH and parent_id=root.
    GenericRecord branchEvent = decodeCreated(created, branchId);
    assertThat(branchEvent.get("type").toString()).isEqualTo("BRANCH");
    assertThat(branchEvent.get("parent_id").toString())
        .isEqualTo(t.rootBusinessUnitId().toString());

    GenericRecord teamEvent = decodeCreated(created, teamId);
    assertThat(teamEvent.get("type").toString()).isEqualTo("TEAM");
    assertThat(teamEvent.get("parent_id").toString()).isEqualTo(outletId.toString());
    assertThat(teamEvent.get("company_id").toString()).isEqualTo(t.companyId().toString());
  }

  @Test
  void anIllegalParentChildTypeIsRejectedAndWritesNothing() throws Exception {
    Tenant t = bootstrapCompany("Beta", "owner-b");
    long createdBefore = orgUnitCreatedRows().size();

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-b",
        () -> {
          // A team directly under the root business_unit skips branch+outlet — illegal.
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("Bad Team", "team", t.rootBusinessUnitId())))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });

    // Nothing persisted and no event emitted (the tx rolled back).
    assertThat(orgUnitCreatedRows()).hasSize((int) createdBefore);
  }

  @Test
  void anUnknownOrCrossTenantParentIsRejected() throws Exception {
    Tenant a = bootstrapCompany("Gamma", "owner-g");
    Tenant b = bootstrapCompany("Delta", "owner-d");

    // B tries to parent a branch under A's root business unit — invisible under RLS, so the
    // parent lookup is empty -> 400, proving the same-company parent constraint.
    TenantContext.callAs(
        b.companyId().toString(),
        "owner-d",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("X Branch", "branch", a.rootBusinessUnitId())))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void aMoveThatWouldCreateACycleIsRejected() throws Exception {
    Tenant t = bootstrapCompany("Epsilon", "owner-e");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-e",
        () -> {
          // root(BU) > branch > outlet
          OrgUnit branch =
              orgUnitService.create(
                  new CreateOrgUnitCommand("Branch", "branch", t.rootBusinessUnitId()));
          orgUnitService.create(new CreateOrgUnitCommand("Outlet", "outlet", branch.getId()));
          // Moving the root business_unit under its own descendant branch would create a cycle.
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              t.rootBusinessUnitId(), null, true, branch.getId(), false)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void renamingAndMovingANodeEachEmitOneOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Zeta", "owner-z");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-z",
            () -> {
              OrgUnit branchA =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Branch A", "branch", t.rootBusinessUnitId()));
              OrgUnit branchB =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Branch B", "branch", t.rootBusinessUnitId()));
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet", "outlet", branchA.getId()));

              // Rename the outlet.
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outlet.getId(), "Renamed Outlet", false, null, false));
              // Move it under branch B.
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outlet.getId(), null, true, branchB.getId(), false));
              return outlet.getId();
            });

    List<Map<String, Object>> changed = orgUnitChangedRows(outletId);
    assertThat(changed).hasSize(2);

    GenericRecord rename = decodeChanged(changed.get(0));
    GenericRecord move = decodeChanged(changed.get(1));
    // One RENAMED and one MOVED, in order.
    assertThat(List.of(rename.get("change_kind").toString(), move.get("change_kind").toString()))
        .containsExactly("RENAMED", "MOVED");
    assertThat(move.get("name").toString()).isEqualTo("Renamed Outlet");

    // The final stored parent is branch B.
    assertThat(parentOf(outletId)).isNotNull();
  }

  @Test
  void deactivatingANodeClosesItsPeriodAndEmitsOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Eta", "owner-h");

    UUID branchId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-h",
            () -> {
              OrgUnit branch =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Branch", "branch", t.rootBusinessUnitId()));
              OrgUnit patched =
                  orgUnitService.patch(
                      new PatchOrgUnitCommand(branch.getId(), null, false, null, true));
              assertThat(patched.isActive()).isFalse();
              return branch.getId();
            });

    List<Map<String, Object>> changed = orgUnitChangedRows(branchId);
    assertThat(changed).hasSize(1);
    GenericRecord deactivated = decodeChanged(changed.get(0));
    assertThat(deactivated.get("change_kind").toString()).isEqualTo("DEACTIVATED");
    assertThat(deactivated.get("active")).isEqualTo(false);
    // active flag is closed in the DB.
    assertThat(isActive(branchId)).isFalse();
  }

  // ---- helpers (read over the admin BYPASSRLS connection / the non-RLS outbox) ----------------

  private UUID parentOf(UUID id) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement("SELECT parent_id FROM org_unit WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject("parent_id", UUID.class);
      }
    }
  }

  private String companyOf(UUID id) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement("SELECT company_id FROM org_unit WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString("company_id");
      }
    }
  }

  private boolean isActive(UUID id) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement("SELECT active FROM org_unit WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean("active");
      }
    }
  }

  private List<Map<String, Object>> orgUnitCreatedRows() {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'OrgUnitCreated' "
            + "ORDER BY occurred_at, id");
  }

  private List<Map<String, Object>> orgUnitChangedRows(UUID orgUnitId) {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'OrgUnitChanged' "
            + "AND aggregate_id = ? ORDER BY occurred_at, id",
        orgUnitId.toString());
  }

  private GenericRecord decodeCreated(List<Map<String, Object>> rows, UUID aggregateId) {
    Map<String, Object> row =
        rows.stream()
            .filter(r -> aggregateId.toString().equals(r.get("aggregate_id")))
            .findFirst()
            .orElseThrow();
    return AvroSerde.deserialize((byte[]) row.get("payload"), OrgUnitCreatedSchema.schema());
  }

  private GenericRecord decodeChanged(Map<String, Object> row) {
    return AvroSerde.deserialize((byte[]) row.get("payload"), OrgUnitChangedSchema.schema());
  }
}
