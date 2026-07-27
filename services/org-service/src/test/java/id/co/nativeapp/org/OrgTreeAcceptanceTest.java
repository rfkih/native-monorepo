package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
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
 *   <li><strong>(a)</strong> a nested {@code business_unit > outlet > team} tree (ADR 0012 — flat,
 *       no branch level) persists under the right parent + company, each node emitting exactly one
 *       {@code OrgUnitCreated} with the right type/parent_id;
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

    // Build outlet > team under the bootstrap root business unit, in A's scope.
    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-a",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet 1", "outlet", t.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Kitchen Team", "team", outlet.getId()));
              return new UUID[] {outlet.getId(), team.getId()};
            });
    UUID outletId = ids[0];
    UUID teamId = ids[1];

    // Each node persisted under the right parent (read over the admin connection).
    assertThat(parentOf(outletId)).isEqualTo(t.rootBusinessUnitId());
    assertThat(parentOf(teamId)).isEqualTo(outletId);
    // And under the right company.
    assertThat(companyOf(outletId)).isEqualTo(t.companyId().toString());
    assertThat(companyOf(teamId)).isEqualTo(t.companyId().toString());

    // Exactly one OrgUnitCreated per node — the 2 new ones + the bootstrap root = 3 total.
    List<Map<String, Object>> created = orgUnitCreatedRows();
    assertThat(created).hasSize(3);

    // The outlet event carries type=OUTLET and parent_id=root.
    GenericRecord outletEvent = decodeCreated(created, outletId);
    assertThat(outletEvent.get("type").toString()).isEqualTo("OUTLET");
    assertThat(outletEvent.get("parent_id").toString())
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
          // A team directly under the root business_unit skips the outlet level — illegal.
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

    // B tries to parent an outlet under A's root business unit — invisible under RLS, so the
    // parent lookup is empty -> 400, proving the same-company parent constraint.
    TenantContext.callAs(
        b.companyId().toString(),
        "owner-d",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("X Outlet", "outlet", a.rootBusinessUnitId())))
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
          // root(BU) > outlet
          OrgUnit outlet =
              orgUnitService.create(
                  new CreateOrgUnitCommand("Outlet", "outlet", t.rootBusinessUnitId()));
          orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outlet.getId()));
          // Moving the root business_unit under its own descendant outlet would create a cycle.
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              t.rootBusinessUnitId(), null, true, outlet.getId(), false, false)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void renamingAndMovingANodeEachEmitOneOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Zeta", "owner-z");

    UUID teamId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-z",
            () -> {
              OrgUnit outletA =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet A", "outlet", t.rootBusinessUnitId()));
              OrgUnit outletB =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet B", "outlet", t.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outletA.getId()));

              // Rename the team.
              orgUnitService.patch(
                  new PatchOrgUnitCommand(team.getId(), "Renamed Team", false, null, false, false));
              // Move it under outlet B.
              orgUnitService.patch(
                  new PatchOrgUnitCommand(team.getId(), null, true, outletB.getId(), false, false));
              return team.getId();
            });

    List<Map<String, Object>> changed = orgUnitChangedRows(teamId);
    assertThat(changed).hasSize(2);

    GenericRecord rename = decodeChanged(changed.get(0));
    GenericRecord move = decodeChanged(changed.get(1));
    // One RENAMED and one MOVED, in order.
    assertThat(List.of(rename.get("change_kind").toString(), move.get("change_kind").toString()))
        .containsExactly("RENAMED", "MOVED");
    assertThat(move.get("name").toString()).isEqualTo("Renamed Team");

    // The final stored parent is outlet B.
    assertThat(parentOf(teamId)).isNotNull();
  }

  @Test
  void deactivatingANodeClosesItsPeriodAndEmitsOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Eta", "owner-h");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-h",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet", "outlet", t.rootBusinessUnitId()));
              OrgUnit patched =
                  orgUnitService.patch(
                      new PatchOrgUnitCommand(outlet.getId(), null, false, null, true, false));
              assertThat(patched.isActive()).isFalse();
              return outlet.getId();
            });

    List<Map<String, Object>> changed = orgUnitChangedRows(outletId);
    assertThat(changed).hasSize(1);
    GenericRecord deactivated = decodeChanged(changed.get(0));
    assertThat(deactivated.get("change_kind").toString()).isEqualTo("DEACTIVATED");
    assertThat(deactivated.get("active")).isEqualTo(false);
    // active flag is closed in the DB.
    assertThat(isActive(outletId)).isFalse();
  }

  @Test
  void deactivatingAnOutletCascadesToItsActiveSubtree() throws Exception {
    Tenant t = bootstrapCompany("Theta", "owner-t");

    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-t",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet", "outlet", t.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outlet.getId()));
              // Deactivate the OUTLET — it must cascade down to the team.
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outlet.getId(), null, false, null, true, false));
              return new UUID[] {outlet.getId(), team.getId()};
            });

    // The whole subtree is inactive; the bootstrap root business unit (the parent) stays up.
    assertThat(isActive(ids[0])).isFalse();
    assertThat(isActive(ids[1])).isFalse();
    assertThat(isActive(t.rootBusinessUnitId())).isTrue();

    // Exactly one DEACTIVATED event per node in the subtree.
    for (UUID id : ids) {
      List<Map<String, Object>> changed = orgUnitChangedRows(id);
      assertThat(changed).hasSize(1);
      GenericRecord event = decodeChanged(changed.get(0));
      assertThat(event.get("change_kind").toString()).isEqualTo("DEACTIVATED");
      assertThat(event.get("active")).isEqualTo(false);
    }
  }

  @Test
  void reactivatingRequiresAnActiveParentAndDoesNotCascadeDown() throws Exception {
    Tenant t = bootstrapCompany("Iota", "owner-i");

    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-i",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("Outlet", "outlet", t.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outlet.getId()));
              // Deactivate the outlet — outlet + team both go inactive (cascade).
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outlet.getId(), null, false, null, true, false));
              return new UUID[] {outlet.getId(), team.getId()};
            });
    UUID outletId = ids[0];
    UUID teamId = ids[1];
    assertThat(isActive(outletId)).isFalse();
    assertThat(isActive(teamId)).isFalse();

    // Reactivating the TEAM while its parent outlet is still inactive is rejected (the
    // invariant).
    TenantContext.callAs(
        t.companyId().toString(),
        "owner-i",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(teamId, null, false, null, false, true)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
    assertThat(isActive(teamId)).isFalse();

    // Reactivate the outlet (its parent, the root BU, is active) — succeeds, does NOT cascade
    // down.
    TenantContext.callAs(
        t.companyId().toString(),
        "owner-i",
        () -> {
          OrgUnit reactivated =
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outletId, null, false, null, false, true));
          assertThat(reactivated.isActive()).isTrue();
          return null;
        });
    assertThat(isActive(outletId)).isTrue();
    assertThat(isActive(teamId)).isFalse(); // reactivation did NOT cascade to the team

    // Now the team's parent is active, so reactivating the team succeeds.
    TenantContext.callAs(
        t.companyId().toString(),
        "owner-i",
        () -> {
          orgUnitService.patch(new PatchOrgUnitCommand(teamId, null, false, null, false, true));
          return null;
        });
    assertThat(isActive(teamId)).isTrue();

    // The outlet emitted a DEACTIVATED then a REACTIVATED (order-independent — a fixed clock could
    // stamp both with the same occurred_at).
    List<String> outletKinds =
        orgUnitChangedRows(outletId).stream()
            .map(row -> decodeChanged(row).get("change_kind").toString())
            .toList();
    assertThat(outletKinds).containsExactlyInAnyOrder("DEACTIVATED", "REACTIVATED");
  }

  @Test
  void movingAnActiveNodeUnderAnInactiveParentIsRejected() throws Exception {
    Tenant t = bootstrapCompany("Kappa", "owner-k");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-k",
        () -> {
          OrgUnit outlet1 =
              orgUnitService.create(
                  new CreateOrgUnitCommand("Outlet 1", "outlet", t.rootBusinessUnitId()));
          OrgUnit outlet2 =
              orgUnitService.create(
                  new CreateOrgUnitCommand("Outlet 2", "outlet", t.rootBusinessUnitId()));
          OrgUnit team =
              orgUnitService.create(new CreateOrgUnitCommand("Team", "team", outlet1.getId()));
          // Deactivate outlet2 (a leaf) — now inactive.
          orgUnitService.patch(
              new PatchOrgUnitCommand(outlet2.getId(), null, false, null, true, false));
          // Moving the ACTIVE team under the INACTIVE outlet2 is rejected (type-legal, but it
          // would orphan an active node under an inactive ancestor).
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              team.getId(), null, true, outlet2.getId(), false, false)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void aPatchThatBothDeactivatesAndReactivatesIsRejected() throws Exception {
    Tenant t = bootstrapCompany("Lambda", "owner-l");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-l",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              t.rootBusinessUnitId(), null, false, null, true, true)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void creatingANodeUnderAnInactiveParentIsRejected() throws Exception {
    Tenant t = bootstrapCompany("Mu", "owner-m");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-m",
        () -> {
          OrgUnit outlet =
              orgUnitService.create(
                  new CreateOrgUnitCommand("Outlet", "outlet", t.rootBusinessUnitId()));
          // Deactivate the outlet, then try to create a team under it — a new node is always
          // active, so this would orphan an active node under an inactive ancestor. Rejected.
          orgUnitService.patch(
              new PatchOrgUnitCommand(outlet.getId(), null, false, null, true, false));
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("Team", "team", outlet.getId())))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });
  }

  @Test
  void aCascadeDeactivationDoesNotTouchAnotherTenantsIdenticalTree() throws Exception {
    Tenant a = bootstrapCompany("Nu", "owner-n");
    Tenant b = bootstrapCompany("Xi", "owner-x");

    // B builds outlet > team under its own root and leaves them active.
    UUID[] bIds =
        TenantContext.callAs(
            b.companyId().toString(),
            "owner-x",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("B Outlet", "outlet", b.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("B Team", "team", outlet.getId()));
              return new UUID[] {outlet.getId(), team.getId()};
            });

    // A builds its own outlet > team and cascade-deactivates the outlet.
    UUID[] aIds =
        TenantContext.callAs(
            a.companyId().toString(),
            "owner-n",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(
                      new CreateOrgUnitCommand("A Outlet", "outlet", a.rootBusinessUnitId()));
              OrgUnit team =
                  orgUnitService.create(new CreateOrgUnitCommand("A Team", "team", outlet.getId()));
              orgUnitService.patch(
                  new PatchOrgUnitCommand(outlet.getId(), null, false, null, true, false));
              return new UUID[] {outlet.getId(), team.getId()};
            });

    // A's subtree went down; B's identical subtree is completely untouched (the cascade's findAll
    // is RLS-scoped to A — no cross-tenant leakage).
    assertThat(isActive(aIds[0])).isFalse();
    assertThat(isActive(aIds[1])).isFalse();
    assertThat(isActive(bIds[0])).isTrue();
    assertThat(isActive(bIds[1])).isTrue();
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
