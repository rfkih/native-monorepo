package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.messaging.CompanyCreatedSchema;
import id.co.nativeapp.org.company.service.CompanyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance (a) — the M1.2 validation goal.
 *
 * <p>Creating a company (bootstrapping a new tenant) persists the company's {@code base_currency} +
 * {@code default_language} + the first business, and writes EXACTLY ONE {@code CompanyCreated} row
 * to the outbox, atomically. The payload deserializes (via {@code libs/events} {@link AvroSerde})
 * to the right {@code company_id} / {@code base_currency} / {@code default_language} / {@code
 * legal_employer_id}.
 *
 * <p>Note there is NO inbound {@code TenantContext} scope around the call: create-company
 * bootstraps its own tenant. The full context boots, so the auto-RLS aspect is active, Flyway has
 * run, and Hibernate {@code ddl-auto=validate} has verified the mapping against the schema — and
 * the RLS {@code WITH CHECK} is satisfied on the bootstrap insert under the unprivileged {@code
 * app_user}.
 */
@SpringBootTest
class CreateCompanyAcceptanceTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner-a@example.co.id";

  @Autowired private CompanyService companyService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void creatingACompanyPersistsCurrencyAndLanguageAndWritesExactlyOneCompanyCreated()
      throws Exception {
    CreateCompanyCommand command =
        new CreateCompanyCommand("Warung Padang", "IDR", "id", "Main Outlet", "restaurant", ACTOR);

    // No TenantContext scope here — create-company bootstraps its own tenant.
    CreateCompanyResult result = companyService.createCompany(command);
    UUID companyId = result.company().getId();

    // The company persisted its immutable base_currency + default_language.
    assertThat(result.company().getBaseCurrency()).isEqualTo("IDR");
    assertThat(result.company().getDefaultLanguage()).isEqualTo("id");
    assertThat(result.company().getLegalEmployerId()).isEqualTo(companyId);

    // The first business persisted as an org unit under the new tenant.
    assertThat(result.firstBusiness().getId()).isNotNull();
    assertThat(result.firstBusiness().getName()).isEqualTo("Main Outlet");

    // The rows landed under the new tenant (counted over the admin BYPASSRLS conn,
    // since company/org_unit are FORCE RLS and have no session GUC on a raw query).
    assertThat(rowCountAsAdmin("company")).isEqualTo(1L);
    // Root business unit + its seeded default outlet (ADR 0012).
    assertThat(rowCountAsAdmin("org_unit")).isEqualTo(2L);

    // EXACTLY ONE CompanyCreated in the outbox.
    List<Map<String, Object>> rows = outboxRows();
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.getFirst();
    assertThat(row.get("event_type")).isEqualTo("CompanyCreated");
    assertThat(row.get("aggregate_type")).isEqualTo("company");
    assertThat(row.get("aggregate_id")).isEqualTo(companyId.toString());
    assertThat(row.get("company_id")).hasToString(companyId.toString());

    // The Avro payload deserializes to the right fields.
    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) row.get("payload"), CompanyCreatedSchema.schema());
    assertThat(decoded.get("company_id").toString()).isEqualTo(companyId.toString());
    assertThat(decoded.get("legal_employer_id").toString()).isEqualTo(companyId.toString());
    assertThat(decoded.get("base_currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("default_language").toString()).isEqualTo("id");
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<Map<String, Object>> outboxRows() {
    // The outbox is not under RLS, so the app role reads every row directly.
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, aggregate_id, company_id, payload "
            + "FROM outbox WHERE event_type = 'CompanyCreated' ORDER BY occurred_at");
  }
}
