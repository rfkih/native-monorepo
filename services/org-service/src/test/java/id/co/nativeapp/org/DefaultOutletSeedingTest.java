package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.OutletResponse;
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
 * ADR 0012 — every new business unit seeds ONE default OUTLET (named after the business unit, in
 * the same transaction, with its own {@code OrgUnitCreated} outbox event) so the POS always has a
 * real outlet to bind to and never falls back to the business-unit id.
 *
 * <p>Covers both seeding sites: company bootstrap ({@code CompanyWriter.create}) and adding another
 * business later ({@code CompanyWriter.addBusiness}). Runs over real RLS-enforcing PostgreSQL (see
 * {@link PostgresRlsTestBase}).
 */
@SpringBootTest
class DefaultOutletSeedingTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void companyBootstrapSeedsOneDefaultOutletUnderTheRootBusinessUnit() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(
                "SeedCo", "IDR", "id", "Warung Seed", "restaurant", "owner-s"));
    UUID companyId = result.company().getId();
    UUID rootId = result.firstBusiness().getId();

    // Exactly one OUTLET row exists, under the root BU, named after the business unit, active.
    List<Map<String, Object>> outlets =
        adminQuery(
            "SELECT id, name, parent_id, active FROM org_unit "
                + "WHERE company_id = ? AND type = 'OUTLET'",
            companyId.toString());
    assertThat(outlets).hasSize(1);
    assertThat(outlets.get(0).get("name")).isEqualTo("Warung Seed");
    assertThat(outlets.get(0).get("parent_id")).isEqualTo(rootId);
    assertThat(outlets.get(0).get("active")).isEqualTo(true);
    UUID outletId = (UUID) outlets.get(0).get("id");

    // The bootstrap emitted TWO OrgUnitCreated events: the root BU and the seeded outlet —
    // atomically with the company (same transaction, rule 3).
    List<Map<String, Object>> created = orgUnitCreatedRows(companyId);
    assertThat(created).hasSize(2);
    GenericRecord outletEvent = decodeCreated(created, outletId);
    assertThat(outletEvent.get("type").toString()).isEqualTo("OUTLET");
    assertThat(outletEvent.get("parent_id").toString()).isEqualTo(rootId.toString());
    assertThat(outletEvent.get("name").toString()).isEqualTo("Warung Seed");
    assertThat(outletEvent.get("company_id").toString()).isEqualTo(companyId.toString());

    // The POS picker sees it immediately.
    List<OutletResponse> visible =
        TenantContext.callAs(
            companyId.toString(), "owner-s", () -> orgUnitService.listActiveOutlets());
    assertThat(visible).hasSize(1);
    assertThat(visible.get(0).id()).isEqualTo(outletId);
    assertThat(visible.get(0).name()).isEqualTo("Warung Seed");
  }

  @Test
  void addingABusinessSeedsItsOwnDefaultOutlet() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("AddCo", "IDR", "id", "First Biz", "restaurant", "owner-a"));
    UUID companyId = result.company().getId();

    OrgUnit secondBu =
        TenantContext.callAs(
            companyId.toString(),
            "owner-a",
            () ->
                companyService.addBusiness(
                    new CreateBusinessCommand(companyId, "Second Biz", "restaurant")));

    // The new BU has its own seeded outlet, named after it.
    List<Map<String, Object>> outlets =
        adminQuery(
            "SELECT id, name, parent_id FROM org_unit "
                + "WHERE company_id = ? AND type = 'OUTLET' ORDER BY created_at",
            companyId.toString());
    assertThat(outlets).hasSize(2); // bootstrap outlet + the new BU's outlet
    assertThat(outlets.get(1).get("name")).isEqualTo("Second Biz");
    assertThat(outlets.get(1).get("parent_id")).isEqualTo(secondBu.getId());

    // Four OrgUnitCreated events total: 2 BUs + 2 seeded outlets.
    assertThat(orgUnitCreatedRows(companyId)).hasSize(4);
  }

  @Test
  void aRootBusinessUnitCreatedViaTheOrgUnitEndpointSeedsItsOwnDefaultOutlet() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand(
                "OrgUnitCo", "IDR", "id", "First Biz", "restaurant", "owner-o"));
    UUID companyId = result.company().getId();

    // The org-tree page creates top-level business units via POST /api/v1/org-units — that
    // path must seed a default outlet too (ADR 0012), not just the CompanyWriter paths.
    OrgUnit thirdBu =
        TenantContext.callAs(
            companyId.toString(),
            "owner-o",
            () ->
                orgUnitService.create(
                    new CreateOrgUnitCommand(
                        "Tree-Made Biz", "business_unit", null, "restaurant")));

    List<Map<String, Object>> outlets =
        adminQuery(
            "SELECT id, name, parent_id FROM org_unit "
                + "WHERE company_id = ? AND type = 'OUTLET' ORDER BY created_at",
            companyId.toString());
    assertThat(outlets).hasSize(2); // bootstrap outlet + the tree-made BU's outlet
    assertThat(outlets.get(1).get("name")).isEqualTo("Tree-Made Biz");
    assertThat(outlets.get(1).get("parent_id")).isEqualTo(thirdBu.getId());

    // Four OrgUnitCreated events total: 2 BUs + 2 seeded outlets.
    assertThat(orgUnitCreatedRows(companyId)).hasSize(4);
  }

  // ---- helpers --------------------------------------------------------------------------------

  private List<Map<String, Object>> adminQuery(String sql, String companyId) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement(sql)) {
      // org_unit.company_id is VARCHAR(64), not uuid — bind as a string.
      ps.setString(1, companyId);
      try (var rs = ps.executeQuery()) {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        var meta = rs.getMetaData();
        while (rs.next()) {
          var row = new java.util.LinkedHashMap<String, Object>();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    }
  }

  /** OrgUnitCreated outbox rows for one company (the outbox is not RLS'd; filter by tenant). */
  private List<Map<String, Object>> orgUnitCreatedRows(UUID companyId) {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'OrgUnitCreated' "
            + "AND company_id = ? ORDER BY occurred_at, id",
        companyId);
  }

  private GenericRecord decodeCreated(List<Map<String, Object>> rows, UUID aggregateId) {
    Map<String, Object> row =
        rows.stream()
            .filter(r -> aggregateId.toString().equals(r.get("aggregate_id")))
            .findFirst()
            .orElseThrow();
    return AvroSerde.deserialize((byte[]) row.get("payload"), OrgUnitCreatedSchema.schema());
  }
}
