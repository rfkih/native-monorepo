package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.messaging.CompanyCreatedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.service.CompanyReader;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The business vertical, end to end, over real RLS-enforcing PostgreSQL (see {@link
 * PostgresRlsTestBase}).
 *
 * <p><strong>ADR 0070 moved it to the COMPANY.</strong> It used to live on the {@code
 * BUSINESS_UNIT} node and be inherited by outlets through a parent self-join; with the division
 * level gone it is a company attribute — required and immutable, like the base currency — and org
 * units carry none at all. This class pins that: the company persists it lowercase and emits it on
 * {@code CompanyCreated}, the org-unit events carry a null vertical, and an unknown value is
 * rejected.
 */
@SpringBootTest
class VerticalAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private CompanyReader companyReader;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapPersistsTheVerticalLowercaseOnTheCompanyAndEmitsItOnCompanyCreated()
      throws Exception {
    var result =
        companyService.createCompany(
            new CreateCompanyCommand("Cuci Kilat", "IDR", "id", "carwash", "owner-v"));
    UUID companyId = result.company().getId();

    // Persisted on the COMPANY, lowercase (the module-key vocabulary), not on the org unit.
    assertThat(result.company().getVertical().key()).isEqualTo("carwash");
    assertThat(columnValue("SELECT vertical FROM company WHERE company_id = ?", companyId))
        .isEqualTo("carwash");
    assertThat(columnValue("SELECT vertical FROM org_unit WHERE company_id = ?", companyId))
        .isNull();

    // CompanyCreated carries it (the field is appended LAST for positional decode safety).
    GenericRecord companyCreated =
        AvroSerde.deserialize(
            payloadOf("CompanyCreated", companyId), CompanyCreatedSchema.schema());
    assertThat(companyCreated.get("vertical").toString()).isEqualTo("carwash");

    // The org-unit event does NOT — an outlet has no vertical of its own any more.
    GenericRecord orgUnitCreated =
        AvroSerde.deserialize(
            payloadOf("OrgUnitCreated", companyId), OrgUnitCreatedSchema.schema());
    assertThat(orgUnitCreated.get("vertical")).isNull();
    assertThat(orgUnitCreated.get("type").toString()).isEqualTo("OUTLET");
  }

  @Test
  void theCompanyReadApiExposesTheVertical() throws Exception {
    UUID companyId =
        companyService
            .createCompany(
                new CreateCompanyCommand("Pangkas Rapi", "IDR", "id", "barbershop", "owner-b"))
            .company()
            .getId();

    // /api/v1/companies/current and /mine both read through here — a cashier's POS bootstraps
    // its surface from this field now that the outlet no longer carries one.
    var response =
        TenantContext.callAs(
            companyId.toString(), "owner-b", () -> companyReader.findCurrentCompany());
    assertThat(response.vertical()).isEqualTo("barbershop");
  }

  @Test
  void aCompanyWithoutAVerticalIsRejected() {
    assertThatThrownBy(
            () ->
                companyService.createCompany(
                    new CreateCompanyCommand("NoVertical", "IDR", "id", null, "owner-n")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnknownVerticalIsRejected() {
    assertThatThrownBy(
            () ->
                companyService.createCompany(
                    new CreateCompanyCommand("Laundry", "IDR", "id", "laundromat", "owner-l")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laundromat");
  }

  @Test
  void anOutletCarriesNoVerticalOfItsOwn() throws Exception {
    UUID companyId =
        companyService
            .createCompany(
                new CreateCompanyCommand("MultiOutlet", "IDR", "id", "restaurant", "owner-m"))
            .company()
            .getId();

    UUID secondId =
        TenantContext.callAs(
            companyId.toString(),
            "owner-m",
            () ->
                orgUnitService
                    .create(new CreateOrgUnitCommand("Senopati", "outlet", null))
                    .getId());

    // Both outlets store NULL; the vertical is read once from the company. Read over the ADMIN
    // connection: a plain JdbcTemplate read here runs with NO tenant GUC bound, so FORCE RLS would
    // filter it to empty and the assertion would pass vacuously.
    assertThat(columnValue("SELECT vertical FROM org_unit WHERE id = ?::uuid", secondId)).isNull();
    assertThat(columnValue("SELECT vertical FROM org_unit WHERE company_id = ?", companyId))
        .isNull();
  }

  // ---- helpers (read over the admin BYPASSRLS connection / the non-RLS outbox) -----------------

  /** A single column for one tenant, read over the admin connection (RLS bypassed). */
  private Object columnValue(String sql, UUID id) throws Exception {
    try (var admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = admin.prepareStatement(sql)) {
      ps.setString(1, id.toString());
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1);
      }
    }
  }

  /** The first outbox payload of one event type for one tenant (the outbox is not RLS'd). */
  private byte[] payloadOf(String eventType, UUID companyId) {
    return jdbcTemplate.queryForObject(
        "SELECT payload FROM outbox WHERE event_type = ? AND company_id = ? "
            + "ORDER BY occurred_at, id LIMIT 1",
        byte[].class,
        eventType,
        companyId);
  }
}
