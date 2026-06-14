package id.co.nativeapp.servicetemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL 16 base for the persistence + RLS tests.
 *
 * <p>The container's default role is a superuser carrying the implicit
 * {@code BYPASSRLS} attribute, so row-level security would never engage for it.
 * The whole point of these tests is to prove the policy <em>does</em> engage, so
 * the base provisions a dedicated, unprivileged {@code app_user} role (no
 * superuser, no BYPASSRLS) and points the Spring {@link javax.sql.DataSource} —
 * and therefore Flyway, Hibernate, and every repository call — at it. That role
 * owns the migrated {@code widget} table; the baseline migration's
 * {@code FORCE ROW LEVEL SECURITY} ensures the policy binds even the owning role,
 * so no connection bypasses tenant isolation.
 *
 * <p><strong>Singleton container pattern.</strong> The container is started once
 * in a static initializer and deliberately never stopped per-class (no
 * {@code @Container}/{@code @Testcontainers} JUnit lifecycle). Spring caches a
 * {@code @SpringBootTest} context keyed by its configuration; since both test
 * classes share this identical configuration, the cached context (and its Hikari
 * pool) is reused across them — which is only safe if the underlying container
 * outlives every class. JUnit-managed per-class containers would stop after the
 * first class and leave the reused context pointing at a dead container; the JVM
 * shutdown hook Testcontainers installs (Ryuk) reaps this one at JVM exit.
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
     * Start every test from an empty {@code widget} table so the two
     * {@code @SpringBootTest} classes (which share one cached context and one
     * container) are order-independent. Truncating over the admin (BYPASSRLS)
     * connection clears rows of every tenant regardless of any session GUC. The
     * table may not yet exist on the very first invocation before Flyway has run,
     * so the truncate is best-effort.
     */
    @BeforeEach
    void resetWidgetTable() {
        try (Connection admin = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = admin.createStatement()) {
            st.execute("TRUNCATE TABLE widget RESTART IDENTITY");
        } catch (SQLException ignored) {
            // Table not created yet (pre-Flyway) — nothing to reset.
        }
    }

    /** Wire Spring's datasource to the unprivileged app role (Flyway runs as it, owning the table). */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static void provisionAppRole() {
        try (Connection admin = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = admin.createStatement()) {
            st.execute(
                    "DO $$ BEGIN "
                            + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN "
                            + "CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'; "
                            + "END IF; END $$");
            // The app role owns the schema so Flyway (running as it) can create the
            // table; PostgreSQL 16 revokes CREATE on public from PUBLIC by default.
            st.execute("GRANT ALL ON SCHEMA public TO " + APP_USER);
            st.execute("GRANT ALL ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to provision the app_user role", e);
        }
    }
}
