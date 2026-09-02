package id.co.nativeapp.org;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL 16 base for the persistence + RLS + outbox tests.
 *
 * <p>The container's default role is a superuser carrying the implicit {@code BYPASSRLS} attribute,
 * so row-level security would never engage for it. The whole point of these tests is to prove the
 * policy <em>does</em> engage, so the base provisions a dedicated, unprivileged {@code app_user}
 * role (no superuser, no BYPASSRLS) and points the Spring {@link javax.sql.DataSource} — and
 * therefore Flyway, Hibernate, and every repository call — at it. That role owns the migrated
 * {@code company}/{@code org_unit} tables; the baseline migration's {@code FORCE ROW LEVEL
 * SECURITY} ensures the policy binds even the owning role, so no connection bypasses tenant
 * isolation. This is also what makes the M1.2 tenant-bootstrap proof meaningful: the create-company
 * insert must satisfy the RLS {@code WITH CHECK} under the unprivileged role.
 *
 * <p><strong>Singleton container pattern.</strong> The container is started once in a static
 * initializer and deliberately never stopped per-class. Spring caches a {@code @SpringBootTest}
 * context keyed by its configuration; since the test classes share this identical configuration,
 * the cached context (and its Hikari pool) is reused across them — only safe if the underlying
 * container outlives every class. Testcontainers' Ryuk shutdown hook reaps it at JVM exit.
 */
abstract class PostgresRlsTestBase {

  static final String APP_USER = "app_user";
  static final String APP_PASSWORD = "app_secret";

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          // withCommand REPLACES the constructor's command (it does not append), so fsync=off —
          // Testcontainers' own default and a large test-time speedup — must be restated here.
          // max_connections is raised from Postgres's default 100 because cached @SpringBootTest
          // contexts each pin a Hikari pool against this one container; restaurant-service died
          // mid-run at ~90 sharing classes with "FATAL: remaining connection slots are reserved
          // for roles with the SUPERUSER attribute" (48ac4add). The cap below is the other half.
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");

  static {
    POSTGRES.start();
    provisionAppRole();
  }

  /**
   * Start every test from empty {@code company} + {@code org_unit} + {@code outbox} tables so the
   * {@code @SpringBootTest} classes (which share one cached context and one container) are
   * order-independent. Truncating over the admin (BYPASSRLS) connection clears rows of every tenant
   * regardless of any session GUC. The tables may not yet exist on the very first invocation before
   * Flyway has run, so the truncate is best-effort.
   */
  @BeforeEach
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE company, org_unit, legal_employer, group_membership,"
              + " consolidation_group, user_outlet_assignment, device_credential, outbox,"
              + " org_tree_flattening_work");
    } catch (SQLException ignored) {
      // Tables not created yet (pre-Flyway) — nothing to reset.
    }
  }

  /**
   * Wire Spring's datasource to the unprivileged app role (Flyway runs as it, owning the tables).
   */
  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    // Hikari defaults to minimumIdle == maximumPoolSize == 10, so every CACHED test context
    // pins 10 idle connections for the rest of the run. Cap the pool and let idle connections
    // drain so cached contexts stay well under the container's max_connections (see above).
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
  }

  private static void provisionAppRole() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "DO $$ BEGIN "
              + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + APP_USER
              + "') THEN "
              + "CREATE ROLE "
              + APP_USER
              + " LOGIN PASSWORD '"
              + APP_PASSWORD
              + "'; "
              + "END IF; END $$");
      // The app role owns the schema so Flyway (running as it) can create the
      // tables; PostgreSQL 16 revokes CREATE on public from PUBLIC by default.
      st.execute("GRANT ALL ON SCHEMA public TO " + APP_USER);
      st.execute("GRANT ALL ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_USER);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to provision the app_user role", e);
    }
  }
}
