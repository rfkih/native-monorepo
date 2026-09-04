package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
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
 * ADR 0070 — the company bootstrap seeds exactly ONE top-level {@code OUTLET}, named after the
 * COMPANY, in the same transaction and with its own {@code OrgUnitCreated} outbox event, so the POS
 * always has a real outlet to bind to.
 *
 * <p>This replaces the ADR 0012 shape it was written for (a root business unit plus a seeded outlet
 * under it, re-seeded on every add-business path): with the division level gone there is one node,
 * one name, and one event. Additional outlets are created flat via {@code POST /api/v1/org-units}.
 *
 * <p>Runs over real RLS-enforcing PostgreSQL (see {@link PostgresRlsTestBase}).
 */
@SpringBootTest
class DefaultOutletSeedingTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void companyBootstrapSeedsExactlyOneTopLevelOutletNamedAfterTheCompany() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("SeedCo", "IDR", "id", "restaurant", "owner-s"));
    UUID companyId = result.company().getId();
    UUID outletId = result.firstBusiness().getId();

    // Exactly one org_unit row: an OUTLET, top-level, named after the COMPANY, active.
    List<Map<String, Object>> units =
        adminQuery(
            "SELECT id, name, type, parent_id, active FROM org_unit WHERE company_id = ?",
            companyId.toString());
    assertThat(units).hasSize(1);
    assertThat(units.get(0).get("id")).isEqualTo(outletId);
    assertThat(units.get(0).get("type")).isEqualTo("OUTLET");
    assertThat(units.get(0).get("name")).isEqualTo("SeedCo");
    assertThat(units.get(0).get("parent_id")).isNull();
    assertThat(units.get(0).get("active")).isEqualTo(true);

    // ONE OrgUnitCreated, atomic with the company (same transaction, rule 3).
    List<Map<String, Object>> created = orgUnitCreatedRows(companyId);
    assertThat(created).hasSize(1);
    GenericRecord event = decodeCreated(created, outletId);
    assertThat(event.get("type").toString()).isEqualTo("OUTLET");
    assertThat(event.get("parent_id")).isNull();
    assertThat(event.get("name").toString()).isEqualTo("SeedCo");
    assertThat(event.get("company_id").toString()).isEqualTo(companyId.toString());
    // The vertical is a COMPANY attribute now — the org-unit event carries none.
    assertThat(event.get("vertical")).isNull();

    // The POS picker sees it immediately.
    List<OutletResponse> visible =
        TenantContext.callAs(
            companyId.toString(), "owner-s", () -> orgUnitService.listActiveOutlets());
    assertThat(visible).hasSize(1);
    assertThat(visible.get(0).id()).isEqualTo(outletId);
    assertThat(visible.get(0).name()).isEqualTo("SeedCo");
  }

  @Test
  void theCompanyCarriesTheVerticalAndTheOutletDoesNot() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("WashCo", "IDR", "id", "carwash", "owner-w"));
    UUID companyId = result.company().getId();

    assertThat(result.company().getVertical().key()).isEqualTo("carwash");

    List<Map<String, Object>> company =
        adminQuery("SELECT vertical FROM company WHERE company_id = ?", companyId.toString());
    assertThat(company).hasSize(1);
    assertThat(company.get(0).get("vertical")).isEqualTo("carwash");

    // The org_unit row stores no vertical of its own any more.
    List<Map<String, Object>> units =
        adminQuery("SELECT vertical FROM org_unit WHERE company_id = ?", companyId.toString());
    assertThat(units).hasSize(1);
    assertThat(units.get(0).get("vertical")).isNull();
  }

  @Test
  void additionalOutletsAreCreatedFlatAndEmitOneEventEach() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("MultiCo", "IDR", "id", "restaurant", "owner-m"));
    UUID companyId = result.company().getId();

    UUID secondId =
        TenantContext.callAs(
            companyId.toString(),
            "owner-m",
            () ->
                orgUnitService.create(new CreateOrgUnitCommand("Kemang", "outlet", null)).getId());

    List<Map<String, Object>> units =
        adminQuery(
            "SELECT id, name, type, parent_id FROM org_unit WHERE company_id = ? ORDER BY created_at",
            companyId.toString());
    assertThat(units).hasSize(2);
    assertThat(units.get(1).get("id")).isEqualTo(secondId);
    assertThat(units.get(1).get("name")).isEqualTo("Kemang");
    assertThat(units.get(1).get("type")).isEqualTo("OUTLET");
    // Flat: the second outlet is top-level too, NOT nested under the first.
    assertThat(units.get(1).get("parent_id")).isNull();

    // One event per outlet — no seeded child to double-count.
    assertThat(orgUnitCreatedRows(companyId)).hasSize(2);
  }

  @Test
  void creatingAnOutletUnderAParentIsRejected() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("FlatCo", "IDR", "id", "restaurant", "owner-f"));
    UUID companyId = result.company().getId();
    UUID firstOutletId = result.firstBusiness().getId();

    // An old client still trying to nest is told so, rather than having the parent silently
    // dropped and getting a differently-shaped tree than it asked for.
    TenantContext.callAs(
        companyId.toString(),
        "owner-f",
        () -> {
          assertThatThrownBy(
                  () ->
                      orgUnitService.create(
                          new CreateOrgUnitCommand("Nested", "outlet", firstOutletId)))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("flat");
          return null;
        });

    // Nothing was created, and nothing was emitted.
    assertThat(adminQuery("SELECT id FROM org_unit WHERE company_id = ?", companyId.toString()))
        .hasSize(1);
    assertThat(orgUnitCreatedRows(companyId)).hasSize(1);
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
