package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Regression pin for the V6→V7 vertical backfill under FORCE ROW LEVEL SECURITY.
 *
 * <p>V6's original backfill UPDATE ran as the table owner with no tenant GUC bound; FORCE RLS
 * filtered it to ZERO rows and Flyway still reported success (caught on the live dev DB — the
 * acceptance tests could not see it because they only create rows AFTER migration). V7 redoes the
 * backfill inside the {@code NO FORCE ROW LEVEL SECURITY} escape hatch (the fleet precedent:
 * restaurant-service V6).
 *
 * <p>This test reproduces the real-world sequence the acceptance tests cannot: migrate to V5 (no
 * {@code vertical} column yet), plant a pre-existing BUSINESS_UNIT row over the admin (BYPASSRLS)
 * connection, then migrate to latest as the unprivileged owner role — exactly how Flyway runs in
 * production — and assert the row was actually backfilled.
 */
class VerticalBackfillMigrationTest {

  private static final String DB = "vertical_backfill_test";

  @Test
  void aPreV6BusinessUnitRowIsBackfilledToRestaurantDespiteForceRls() throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES; // starts container + app role
    String testDbUrl =
        "jdbc:postgresql://"
            + pg.getHost()
            + ":"
            + pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + DB;

    // Fresh database for this test only — the shared one is already migrated to latest.
    try (Connection admin =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + DB + " (FORCE)");
      st.execute("CREATE DATABASE " + DB);
      st.execute("GRANT ALL ON DATABASE " + DB + " TO " + PostgresRlsTestBase.APP_USER);
    }
    try (Connection admin =
            DriverManager.getConnection(testDbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement()) {
      // PostgreSQL 16 revokes CREATE on public from PUBLIC — the migrating role needs it back.
      st.execute("GRANT ALL ON SCHEMA public TO " + PostgresRlsTestBase.APP_USER);
    }

    // 1) Migrate to V5 as the unprivileged owner role (how Flyway runs for real): no vertical yet.
    flyway(testDbUrl).target("5").load().migrate();

    // 2) Plant a pre-V6 BUSINESS_UNIT row over the admin BYPASSRLS connection.
    UUID buId = UUID.randomUUID();
    try (Connection admin =
            DriverManager.getConnection(testDbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "INSERT INTO org_unit (id, name, type, parent_id, created_at, created_by, updated_at,"
              + " updated_by, version, company_id, legal_employer_id) VALUES ('"
              + buId
              + "', 'Pre-V6 Biz', 'BUSINESS_UNIT', NULL, now(), 'mig-test', now(), 'mig-test', 0,"
              + " 'pre-v6-company', '"
              + UUID.randomUUID()
              + "')");
    }

    // 3) Migrate to latest as the owner role. Without V7's NO FORCE escape hatch, FORCE RLS
    //    silently filters the backfill UPDATE to zero rows.
    flyway(testDbUrl).load().migrate();

    try (Connection admin =
            DriverManager.getConnection(testDbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT vertical FROM org_unit WHERE id = '" + buId + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("restaurant");
    }
  }

  private static FluentConfiguration flyway(String url) {
    return Flyway.configure()
        .dataSource(url, PostgresRlsTestBase.APP_USER, PostgresRlsTestBase.APP_PASSWORD)
        .locations("classpath:db/migration");
  }
}
