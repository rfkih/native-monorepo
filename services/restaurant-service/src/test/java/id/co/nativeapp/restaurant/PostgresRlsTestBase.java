package id.co.nativeapp.restaurant;

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
 * {@code sale} table; the baseline migration's {@code FORCE ROW LEVEL SECURITY} ensures the policy
 * binds even the owning role, so no connection bypasses tenant isolation.
 *
 * <p><strong>Singleton container pattern.</strong> The container is started once in a static
 * initializer and deliberately never stopped per-class. Spring caches a {@code @SpringBootTest}
 * context keyed by its configuration; since the test classes share this identical configuration,
 * the cached context (and its Hikari pool) is reused across them — only safe if the underlying
 * container outlives every class. Testcontainers' Ryuk shutdown hook reaps it at JVM exit.
 */
public abstract class PostgresRlsTestBase {

  static final String APP_USER = "app_user";
  static final String APP_PASSWORD = "app_secret";

  /**
   * Protected (not package-private) so test classes in feature sub-packages — e.g. {@code
   * id.co.nativeapp.restaurant.order} — can read the container's JDBC coordinates to open an
   * admin/BYPASSRLS connection for cross-tenant row-count assertions.
   *
   * <p>{@code max_connections} is raised from Postgres's default 100: ~90 test classes share this
   * one container, Spring's context cache keeps up to 32 distinct {@code @SpringBootTest} contexts
   * alive, and each holds a Hikari pool — at default pool sizing that exceeds 100 and the suite
   * dies mid-run with {@code FATAL: remaining connection slots are reserved for roles with the
   * SUPERUSER attribute} (two contexts then fail Flyway's connect, and any raw {@code app_user}
   * DriverManager connection is refused). The Hikari cap in {@link #datasourceProperties} is the
   * other half of the same fix.
   */
  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          // withCommand REPLACES the constructor's command (it does not append), so fsync=off —
          // Testcontainers' own default, a large test-time speedup — must be restated here.
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");

  static {
    POSTGRES.start();
    provisionAppRole();
  }

  /**
   * Start every test from empty {@code sale} + {@code outbox} tables so the {@code @SpringBootTest}
   * classes (which share one cached context and one container) are order-independent. Truncating
   * over the admin (BYPASSRLS) connection clears rows of every tenant regardless of any session
   * GUC. The tables may not yet exist on the very first invocation before Flyway has run, so the
   * truncate is best-effort.
   */
  @BeforeEach
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE applied_promotion, coupon, promo_rule,"
              + " bill_line_modifier, bill_line, bill,"
              + " payment, order_line_modifier, order_line, restaurant_order,"
              + " stocktake_line, stocktake,"
              + " recipe_line,"
              + " goods_receipt,"
              + " ingredient_stocktake_line, ingredient_stocktake, ingredient,"
              + " restaurant_table, menu_item_modifier_option, menu_item_modifier_group, menu_item,"
              + " menu_category, sale, sales_channel, gift_card_sale, gift_card_ref,"
              + " member_balance_ref, outbox,"
              + " user_outlet_assignment_ref, processed_event, error_log,"
              + " self_order_access, entitlement_projection CASCADE");
    } catch (SQLException ignored) {
      // Tables not created yet (pre-Flyway) — nothing to reset.
    }
  }

  /**
   * Wire Spring's datasource to the unprivileged app role (Flyway runs as it, owning the table).
   *
   * <p>Also pins {@code spring.kafka.bootstrap-servers} to a non-routable address: no test in this
   * suite needs a broker (the {@code UserOutletAssignmentChanged} consumer is exercised at the
   * service layer), but the Phase-5 {@code @KafkaListener} container starts with the context and
   * would otherwise connect to whatever happens to be listening on the developer's {@code
   * localhost:9092} — on this machine a FOREIGN Kafka broker from an unrelated stack. Pointing it
   * at a closed port keeps the listener retrying harmlessly in the background.
   */
  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    // Hikari defaults to minimumIdle == maximumPoolSize == 10, so every CACHED test context
    // pins 10 idle connections for the rest of the run. Cap the pool and let idle connections
    // drain so 32 cached contexts stay well under the container's max_connections (see POSTGRES).
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
