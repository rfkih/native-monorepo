package id.co.nativeapp.employee;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL 16 base for the persistence + RLS + PII + idempotency tests.
 *
 * <p>The container's default role is a superuser carrying the implicit {@code BYPASSRLS} attribute,
 * so row-level security would never engage for it. The whole point of these tests is to prove the
 * policy <em>does</em> engage, so the base provisions a dedicated, unprivileged {@code app_user}
 * role (no superuser, no BYPASSRLS) and points the Spring {@link javax.sql.DataSource} — and
 * therefore Flyway, Hibernate, and every repository call — at it. That role owns the migrated
 * tables; the baseline migration's {@code FORCE ROW LEVEL SECURITY} ensures the policy binds even
 * the owning role, so no connection bypasses tenant isolation (rule 5).
 *
 * <p><strong>Singleton container pattern.</strong> The container is started once in a static
 * initializer and deliberately never stopped per-class; Spring caches a {@code @SpringBootTest}
 * context keyed by its configuration, and the test classes share this identical configuration, so
 * the cached context (and its Hikari pool) is reused. Testcontainers' Ryuk reaps the container at
 * JVM exit.
 */
abstract class PostgresRlsTestBase {

  static final String APP_USER = "app_user";
  static final String APP_PASSWORD = "app_secret";

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  static {
    POSTGRES.start();
    provisionAppRole();
  }

  /**
   * Start every test from empty tables so the {@code @SpringBootTest} classes (which share one
   * cached context and one container) are order-independent. Truncating over the admin (BYPASSRLS)
   * connection clears rows of every tenant regardless of any session GUC. The tables may not exist
   * on the very first invocation before Flyway has run, so the truncate is best-effort.
   */
  @BeforeEach
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE employee, employment_contract, assignment, org_unit_projection,"
              + " comp_package, pay_component, earning_rule, statutory_rule, payroll_run,"
              + " payslip_line, labor_cost_allocation, metric_input, period_seal,"
              + " expense_category, expense_claim, expense_claim_event, expense_receipt,"
              + " leave_request, overtime_entry, leave_balance, work_calendar,"
              + " timeoff_request_event, operator_pin, outlet_operator_policy,"
              + " outbox, processed_event");
    } catch (SQLException ignored) {
      // Tables not created yet (pre-Flyway) — nothing to reset.
    }
  }

  /**
   * Runs a scalar count as the unprivileged {@code app_user} under the given tenant's RLS scope.
   * Raw test-side JDBC carries no tenant GUC (the {@code RlsAutoApplyAspect} binds only
   * {@code @Transactional} beans — see the rls-guc-transactional-only gotcha), so FORCE RLS
   * correctly hides every row from a bare connection or {@code JdbcTemplate}. This helper is how a
   * test asserts row-level truth without weakening anything: a FRESH {@code app_user} session,
   * {@code set_config('app.current_tenant', tenant)}, one query, connection closed — the GUC dies
   * with the session, so nothing leaks across tests or pooled connections.
   */
  static long countAsTenant(String tenant, String sql, Object... args) {
    try (Connection con =
        java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
      try (java.sql.PreparedStatement set =
          con.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
        set.setString(1, tenant);
        set.execute();
      }
      try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
        for (int i = 0; i < args.length; i++) {
          ps.setObject(i + 1, args[i]);
        }
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("countAsTenant failed for tenant " + tenant, e);
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
      // The app role owns the schema so Flyway (running as it) can create the tables;
      // PostgreSQL 16 revokes CREATE on public from PUBLIC by default.
      st.execute("GRANT ALL ON SCHEMA public TO " + APP_USER);
      st.execute("GRANT ALL ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_USER);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to provision the app_user role", e);
    }
  }
}
