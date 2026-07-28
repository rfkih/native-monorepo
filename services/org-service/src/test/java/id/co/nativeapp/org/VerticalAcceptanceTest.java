package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance for the business-unit {@code vertical} (restaurant | carwash | barbershop): required
 * for a BUSINESS_UNIT on every creation path, forbidden for outlet/team, persisted LOWERCASE, and
 * carried on the org-unit events (null for the seeded outlet). Runs over real RLS-enforcing
 * PostgreSQL (see {@link PostgresRlsTestBase}).
 */
@SpringBootTest
class VerticalAcceptanceTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner-vert";

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapPersistsTheVerticalLowercaseAndEmitsItOnTheEvents() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("WashCo", "IDR", "id", "Wash HQ", "carwash", ACTOR));
    UUID companyId = result.company().getId();
    UUID buId = result.firstBusiness().getId();

    // Persisted LOWERCASE on the BU row; NULL on the seeded outlet.
    assertThat(verticalOf(buId)).isEqualTo("carwash");
    UUID outletId = seededOutletId(companyId, buId);
    assertThat(verticalOf(outletId)).isNull();

    // Both OrgUnitCreated payloads carry the field: BU = "carwash", outlet = null.
    List<Map<String, Object>> created = orgUnitCreatedRows(companyId);
    assertThat(created).hasSize(2);
    assertThat(decodeCreated(created, buId).get("vertical").toString()).isEqualTo("carwash");
    assertThat(decodeCreated(created, outletId).get("vertical")).isNull();
  }

  @Test
  void aBusinessUnitWithoutAVerticalIsRejected() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("NoVertCo", "IDR", "id", "First", "restaurant", ACTOR));
    UUID companyId = result.company().getId();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyId.toString(),
                    ACTOR,
                    () ->
                        orgUnitService.create(
                            new CreateOrgUnitCommand("Typeless Biz", "business_unit", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vertical");
  }

  @Test
  void anUnknownVerticalIsRejected() throws Exception {
    assertThatThrownBy(
            () ->
                companyService.createCompany(
                    new CreateCompanyCommand(
                        "LaundryCo", "IDR", "id", "Laundry HQ", "laundromat", ACTOR)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laundromat");
  }

  @Test
  void anOutletWithAVerticalIsRejected() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("StrictCo", "IDR", "id", "Strict HQ", "restaurant", ACTOR));
    UUID companyId = result.company().getId();
    UUID buId = result.firstBusiness().getId();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    companyId.toString(),
                    ACTOR,
                    () ->
                        orgUnitService.create(
                            new CreateOrgUnitCommand(
                                "Typed Outlet", "outlet", buId, "restaurant"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vertical");
  }

  @Test
  void addBusinessPersistsAndEmitsItsVertical() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("MultiCo", "IDR", "id", "First Biz", "restaurant", ACTOR));
    UUID companyId = result.company().getId();

    OrgUnit second =
        TenantContext.callAs(
            companyId.toString(),
            ACTOR,
            () ->
                companyService.addBusiness(
                    new CreateBusinessCommand(companyId, "Cuts Biz", "barbershop")));

    assertThat(verticalOf(second.getId())).isEqualTo("barbershop");
    assertThat(decodeCreated(orgUnitCreatedRows(companyId), second.getId()).get("vertical"))
        .hasToString("barbershop");
  }

  @Test
  void aRenameChangeEventCarriesTheNodesOwnVertical() throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("RenameCo", "IDR", "id", "Wash HQ", "carwash", ACTOR));
    UUID companyId = result.company().getId();
    UUID buId = result.firstBusiness().getId();

    TenantContext.runAs(
        companyId.toString(),
        ACTOR,
        () ->
            orgUnitService.patch(
                new PatchOrgUnitCommand(buId, "Wash Central", false, null, false, false)));

    List<Map<String, Object>> changed =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'OrgUnitChanged'"
                + " AND aggregate_id = ? ORDER BY occurred_at",
            buId.toString());
    assertThat(changed).hasSize(1);
    GenericRecord event =
        AvroSerde.deserialize(
            (byte[]) changed.get(0).get("payload"), OrgUnitChangedSchema.schema());
    assertThat(event.get("vertical").toString()).isEqualTo("carwash");
    assertThat(event.get("name").toString()).isEqualTo("Wash Central");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private String verticalOf(UUID orgUnitId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT vertical FROM org_unit WHERE id = ?")) {
      ps.setObject(1, orgUnitId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private UUID seededOutletId(UUID companyId, UUID buId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM org_unit WHERE company_id = ? AND parent_id = ?"
                    + " AND type = 'OUTLET'")) {
      ps.setString(1, companyId.toString());
      ps.setObject(2, buId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private List<Map<String, Object>> orgUnitCreatedRows(UUID companyId) {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'OrgUnitCreated'"
            + " AND company_id = ? ORDER BY occurred_at, id",
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
