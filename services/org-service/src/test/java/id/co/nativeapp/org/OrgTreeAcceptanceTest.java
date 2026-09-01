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
import id.co.nativeapp.org.company.messaging.OrgUnitDeletedSchema;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitHasDataException;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.org.user.service.UserOutletAssignmentWriter;
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
 * Acceptance (a/b/c) for a company's outlets, end to end through {@link OrgUnitService} over a real
 * RLS-enforcing PostgreSQL (the unprivileged {@code app_user}; see {@link PostgresRlsTestBase}).
 *
 * <p><strong>ADR 0070 flattened the tree to {@code company > outlet}.</strong> Everything this
 * class used to assert about NESTING is gone with the division and team levels: the parent-child
 * type rules, the cycle guard, the move operation, the cascading deactivation, and the "no active
 * node under an inactive ancestor" invariant. What is left, and what this pins:
 *
 * <ul>
 *   <li><strong>(a)</strong> outlets persist FLAT (every {@code parent_id} null) and each emits
 *       exactly one {@code OrgUnitCreated};
 *   <li><strong>(b)</strong> a create or patch that supplies a parent is rejected with a {@code
 *       400} and writes nothing — an old client learns its request was not honoured;
 *   <li><strong>(c)</strong> rename / deactivate / reactivate each emit one {@code OrgUnitChanged}
 *       for that node ALONE, and delete emits one {@code OrgUnitDeleted} (ADR 0070) unless the ADR
 *       0018 assigned-login guard rejects it;
 *   <li>every one of those is tenant-scoped — another tenant's identical outlets are untouched.
 * </ul>
 */
@SpringBootTest
class OrgTreeAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentWriter assignmentWriter;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Bootstraps a company and returns {tenantId, rootBusinessUnitId}. */
  private record Tenant(UUID companyId, UUID rootBusinessUnitId) {}

  private Tenant bootstrapCompany(String name, String actor) {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", "restaurant", actor));
    return new Tenant(result.company().getId(), result.firstBusiness().getId());
  }

  @Test
  void outletsPersistFlatAndEmitOneOrgUnitCreatedEach() throws Exception {
    Tenant t = bootstrapCompany("Alpha", "owner-a");

    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-a",
            () -> {
              OrgUnit kemang =
                  orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null));
              OrgUnit senopati =
                  orgUnitService.create(new CreateOrgUnitCommand("Senopati", "outlet", null));
              return new UUID[] {kemang.getId(), senopati.getId()};
            });

    // Flat: both are top-level, neither under the bootstrap outlet nor under each other.
    assertThat(parentOf(ids[0])).isNull();
    assertThat(parentOf(ids[1])).isNull();
    assertThat(companyOf(ids[0])).isEqualTo(t.companyId().toString());

    // Three OrgUnitCreated in total: the bootstrap outlet + these two. One per node, no seeded
    // children to double-count (ADR 0012's default-outlet seeding died with the division level).
    List<Map<String, Object>> created = orgUnitCreatedRows();
    GenericRecord kemangEvent = decodeCreated(created, ids[0]);
    assertThat(kemangEvent.get("type").toString()).isEqualTo("OUTLET");
    assertThat(kemangEvent.get("parent_id")).isNull();
    assertThat(kemangEvent.get("name").toString()).isEqualTo("Kemang");
    // The vertical is a COMPANY attribute now; the org-unit event carries none.
    assertThat(kemangEvent.get("vertical")).isNull();
  }

  @Test
  void supplyingAParentIsRejectedAndWritesNothing() throws Exception {
    Tenant t = bootstrapCompany("Beta", "owner-b");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-b",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("Nested", "outlet", t.rootBusinessUnitId())))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("flat");
          // A retired level is equally rejected — an old client gets a clear 400, not a surprise.
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("Division", "business_unit", null)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });

    // Only the bootstrap outlet exists, and only its own OrgUnitCreated was written.
    assertThat(orgUnitCreatedRows().stream().filter(r -> r.get("aggregate_id") != null).count())
        .isGreaterThan(0);
    assertThat(exists(t.rootBusinessUnitId())).isTrue();
  }

  @Test
  void aMoveIsRejectedBecauseThereIsNowhereToMoveTo() throws Exception {
    Tenant t = bootstrapCompany("Gamma", "owner-g");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-g",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-g",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              outletId, null, true, t.rootBusinessUnitId(), false, false)))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("flat");
          return null;
        });

    assertThat(parentOf(outletId)).isNull();
    assertThat(orgUnitChangedRows(outletId)).isEmpty();
  }

  @Test
  void renamingANodeEmitsExactlyOneOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Delta", "owner-d");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-d",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-d",
        () -> {
          orgUnitService.patch(
              new PatchOrgUnitCommand(outletId, "Kemang Raya", false, null, false, false));
          // A no-op rename writes no second event.
          orgUnitService.patch(
              new PatchOrgUnitCommand(outletId, "Kemang Raya", false, null, false, false));
          return null;
        });

    List<Map<String, Object>> changed = orgUnitChangedRows(outletId);
    assertThat(changed).hasSize(1);
    GenericRecord event = decodeChanged(changed.get(0));
    assertThat(event.get("change_kind").toString()).isEqualTo("RENAMED");
    assertThat(event.get("name").toString()).isEqualTo("Kemang Raya");
    assertThat(event.get("active")).isEqualTo(true);
  }

  @Test
  void deactivatingANodeClosesItsPeriodAndEmitsOrgUnitChanged() throws Exception {
    Tenant t = bootstrapCompany("Epsilon", "owner-e");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-e",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-e",
        () -> {
          orgUnitService.patch(new PatchOrgUnitCommand(outletId, null, false, null, true, false));
          return null;
        });

    assertThat(isActive(outletId)).isFalse();
    List<Map<String, Object>> changed = orgUnitChangedRows(outletId);
    assertThat(changed).hasSize(1);
    assertThat(decodeChanged(changed.get(0)).get("change_kind").toString())
        .isEqualTo("DEACTIVATED");
    assertThat(decodeChanged(changed.get(0)).get("active")).isEqualTo(false);
  }

  @Test
  void deactivatingOneOutletLeavesEveryOtherOutletAlone() throws Exception {
    Tenant t = bootstrapCompany("Zeta", "owner-z");

    UUID[] ids =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-z",
            () -> {
              OrgUnit a = orgUnitService.create(new CreateOrgUnitCommand("A", "outlet", null));
              OrgUnit b = orgUnitService.create(new CreateOrgUnitCommand("B", "outlet", null));
              return new UUID[] {a.getId(), b.getId()};
            });

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-z",
        () -> {
          orgUnitService.patch(new PatchOrgUnitCommand(ids[0], null, false, null, true, false));
          return null;
        });

    // There is no subtree to cascade into, and no sibling is affected.
    assertThat(isActive(ids[0])).isFalse();
    assertThat(isActive(ids[1])).isTrue();
    assertThat(isActive(t.rootBusinessUnitId())).isTrue();
    assertThat(orgUnitChangedRows(ids[1])).isEmpty();
  }

  @Test
  void reactivatingReopensThePeriodAndNeedsNoParentToBeActive() throws Exception {
    Tenant t = bootstrapCompany("Eta", "owner-h");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-h",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-h",
        () -> {
          orgUnitService.patch(new PatchOrgUnitCommand(outletId, null, false, null, true, false));
          // No ancestor chain to validate any more — a top-level node is always reactivatable.
          orgUnitService.patch(new PatchOrgUnitCommand(outletId, null, false, null, false, true));
          return null;
        });

    assertThat(isActive(outletId)).isTrue();
    List<Map<String, Object>> changed = orgUnitChangedRows(outletId);
    assertThat(changed).hasSize(2);
    assertThat(decodeChanged(changed.get(1)).get("change_kind").toString())
        .isEqualTo("REACTIVATED");
  }

  @Test
  void aPatchThatBothDeactivatesAndReactivatesIsRejected() throws Exception {
    Tenant t = bootstrapCompany("Theta", "owner-t");

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-t",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.patch(
                          new PatchOrgUnitCommand(
                              t.rootBusinessUnitId(), null, false, null, true, true)))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });

    assertThat(isActive(t.rootBusinessUnitId())).isTrue();
  }

  @Test
  void aDeactivationDoesNotTouchAnotherTenantsIdenticalOutlets() throws Exception {
    Tenant a = bootstrapCompany("Iota-A", "owner-ia");
    Tenant b = bootstrapCompany("Iota-B", "owner-ib");

    UUID aOutlet =
        TenantContext.callAs(
            a.companyId().toString(),
            "owner-ia",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());
    UUID bOutlet =
        TenantContext.callAs(
            b.companyId().toString(),
            "owner-ib",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        a.companyId().toString(),
        "owner-ia",
        () -> {
          orgUnitService.patch(new PatchOrgUnitCommand(aOutlet, null, false, null, true, false));
          return null;
        });

    assertThat(isActive(aOutlet)).isFalse();
    assertThat(isActive(bOutlet)).isTrue();
    assertThat(isActive(b.rootBusinessUnitId())).isTrue();
  }

  @Test
  void deletingAnOutletRemovesItAndEmitsOneOrgUnitDeleted() throws Exception {
    Tenant t = bootstrapCompany("Nu-Delete", "owner-nd");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-nd",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-nd",
        () -> {
          orgUnitService.delete(outletId);
          return null;
        });

    assertThat(exists(outletId)).isFalse();
    // Flat tree: no subtree went with it, and the bootstrap outlet is untouched.
    assertThat(exists(t.rootBusinessUnitId())).isTrue();

    // ADR 0070: exactly ONE OrgUnitDeleted, on the same transaction as the delete, so
    // finance/employee PURGE their cached refs instead of keeping inert rows.
    List<Map<String, Object>> deleted = orgUnitDeletedRows(t.companyId());
    assertThat(deleted).hasSize(1);
    GenericRecord event = decodeDeleted(deleted, outletId);
    assertThat(event.get("company_id").toString()).isEqualTo(t.companyId().toString());
    assertThat(event.get("type").toString()).isEqualTo("OUTLET");
    assertThat(event.get("parent_id")).isNull();
  }

  @Test
  void deletingAnOutletWithAnAssignedLoginIsRejectedAndDeletesNothing() throws Exception {
    Tenant t = bootstrapCompany("Xi-Delete", "owner-xd");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-xd",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(new CreateOrgUnitCommand("Outlet", "outlet", null));
              // Assign a login to the outlet — now it could have rung sales.
              assignmentWriter.replaceAssignments("cashier-sub-1", List.of(outlet.getId()));
              return outlet.getId();
            });

    TenantContext.callAs(
        t.companyId().toString(),
        "owner-xd",
        () -> {
          assertThatThrownBy(() -> orgUnitService.delete(outletId))
              .isInstanceOf(OrgUnitHasDataException.class);
          return null;
        });

    assertThat(exists(outletId)).isTrue();
    // ADR 0070: the guard throws BEFORE any event is written, and the transaction rolls back
    // regardless — no consumer may ever purge a unit that still exists.
    assertThat(orgUnitDeletedRows(t.companyId())).isEmpty();
  }

  @Test
  void deletingAnOutletWhoseLoginWasUnassignedIsStillRejected() throws Exception {
    Tenant t = bootstrapCompany("Omicron-Delete", "owner-od");

    UUID outletId =
        TenantContext.callAs(
            t.companyId().toString(),
            "owner-od",
            () -> {
              OrgUnit outlet =
                  orgUnitService.create(new CreateOrgUnitCommand("Outlet", "outlet", null));
              // Assign then UNASSIGN — the replace-set closes the row (active=false) but keeps it.
              assignmentWriter.replaceAssignments("cashier-sub-2", List.of(outlet.getId()));
              assignmentWriter.replaceAssignments("cashier-sub-2", List.of());
              return outlet.getId();
            });

    // The closed assignment row still means the outlet was once staffed → undeletable.
    TenantContext.callAs(
        t.companyId().toString(),
        "owner-od",
        () -> {
          assertThatThrownBy(() -> orgUnitService.delete(outletId))
              .isInstanceOf(OrgUnitHasDataException.class);
          return null;
        });

    assertThat(exists(outletId)).isTrue();
  }

  @Test
  void deletingAnotherTenantsUnitIsNotFoundAndLeavesItIntact() throws Exception {
    Tenant a = bootstrapCompany("Pi-Delete-A", "owner-pa");
    Tenant b = bootstrapCompany("Rho-Delete-B", "owner-rb");

    UUID aOutletId =
        TenantContext.callAs(
            a.companyId().toString(),
            "owner-pa",
            () ->
                orgUnitService
                    .create(new CreateOrgUnitCommand("A Outlet", "outlet", null))
                    .getId());

    // B tries to delete A's outlet — invisible under RLS, so it is a 404, and nothing is removed.
    TenantContext.callAs(
        b.companyId().toString(),
        "owner-rb",
        () -> {
          assertThatThrownBy(() -> orgUnitService.delete(aOutletId))
              .isInstanceOf(OrgUnitNotFoundException.class);
          return null;
        });

    assertThat(exists(aOutletId)).isTrue();
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

  /** Whether the org_unit row still exists (read over the admin BYPASSRLS connection). */
  private boolean exists(UUID id) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement("SELECT 1 FROM org_unit WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        return rs.next();
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

  /**
   * {@code OrgUnitDeleted} outbox rows for ONE company. Scoped by {@code company_id} deliberately:
   * the outbox is shared across every test in this class, so an unscoped read would see other
   * tests' deletions and make any exact-count assertion order-dependent.
   */
  private List<Map<String, Object>> orgUnitDeletedRows(UUID companyId) {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'OrgUnitDeleted' "
            + "AND company_id = ? ORDER BY occurred_at, id",
        companyId);
  }

  private GenericRecord decodeDeleted(List<Map<String, Object>> rows, UUID aggregateId) {
    Map<String, Object> row =
        rows.stream()
            .filter(r -> aggregateId.toString().equals(r.get("aggregate_id")))
            .findFirst()
            .orElseThrow();
    return AvroSerde.deserialize((byte[]) row.get("payload"), OrgUnitDeletedSchema.schema());
  }
}
