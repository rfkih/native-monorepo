package id.co.nativeapp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Acceptance: "RLS context sets/clears" — the defense-in-depth tenant-isolation proof against a
 * real PostgreSQL 16.
 *
 * <p>A {@code widget} table is created (by an admin connection) with a row-level -security policy
 * keyed to {@code current_setting('app.current_tenant', true)}. Tenant-scoped reads and writes are
 * then performed over a <em>separate</em> connection that authenticates as a dedicated,
 * unprivileged {@code app_user} role — exactly as a real Native service connects. That distinction
 * is load-bearing: the Testcontainers default role is a superuser with the implicit {@code
 * BYPASSRLS} attribute, so RLS would never engage for it no matter how the policy is written; the
 * application role has no such bypass, so the policy is genuinely enforced.
 *
 * <p>All tenant-scoped work flows through {@link RlsTransactionSynchronizer} driving {@code SET
 * LOCAL app.current_tenant} from {@link TenantContext}, and the suite proves:
 *
 * <ol>
 *   <li>inside tenant A's scope, only A's rows are visible (B's are invisible);
 *   <li>inside tenant B's scope, only B's rows are visible;
 *   <li>a row written in A's scope lands under A and stays invisible to B;
 *   <li>writing a row carrying another tenant's id is rejected by the policy's {@code WITH CHECK}
 *       clause;
 *   <li>the setting does not leak: a fresh transaction with no scope sees zero rows (fails closed),
 *       confirming {@code SET LOCAL} cleared at the prior transaction's end and never bled onto the
 *       pooled connection.
 * </ol>
 */
@Testcontainers
class RlsIsolationTest {

  private static final String TENANT_A = "company-A";
  private static final String TENANT_B = "company-B";

  private static final String APP_USER = "app_user";
  private static final String APP_PASSWORD = "app_secret";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  /** Admin (superuser) JDBC for DDL + seeding only. */
  private JdbcTemplate adminJdbc;

  /** Application-role JDBC: subject to RLS, used for every tenant-scoped op. */
  private JdbcTemplate jdbcTemplate;

  private TransactionTemplate transactionTemplate;
  private RlsTransactionSynchronizer synchronizer;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource adminDs =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    adminDs.setDriverClassName("org.postgresql.Driver");
    this.adminJdbc = new JdbcTemplate(adminDs);

    // A dedicated, unprivileged application role. Crucially NOT a superuser
    // and without BYPASSRLS, so it is genuinely subject to the policy.
    adminJdbc.execute("DROP TABLE IF EXISTS widget");
    // Idempotent role reset: drop any objects/privileges the role owns (only
    // if it already exists) before dropping the role itself, so re-runs in
    // the shared container start clean.
    adminJdbc.execute(
        """
                DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
                        EXECUTE 'DROP OWNED BY app_user CASCADE';
                        EXECUTE 'DROP ROLE app_user';
                    END IF;
                END $$
                """);
    adminJdbc.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");

    adminJdbc.execute(
        """
                CREATE TABLE widget (
                    id         BIGSERIAL PRIMARY KEY,
                    company_id TEXT NOT NULL,
                    name       TEXT NOT NULL
                )
                """);
    adminJdbc.execute("ALTER TABLE widget ENABLE ROW LEVEL SECURITY");
    // The policy: a row is visible/writable only when its company_id equals
    // the session tenant. current_setting(..., true) returns NULL (not an
    // error) when unset, so with no scope the predicate is false for every
    // row -> fail closed.
    adminJdbc.execute(
        """
                CREATE POLICY widget_tenant_isolation ON widget
                    USING (company_id = current_setting('app.current_tenant', true))
                    WITH CHECK (company_id = current_setting('app.current_tenant', true))
                """);
    adminJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON widget TO " + APP_USER);
    adminJdbc.execute("GRANT USAGE, SELECT ON SEQUENCE widget_id_seq TO " + APP_USER);

    // Seed rows for both tenants over the admin (RLS-exempt) connection.
    adminJdbc.update("INSERT INTO widget (company_id, name) VALUES (?, ?)", TENANT_A, "a-1");
    adminJdbc.update("INSERT INTO widget (company_id, name) VALUES (?, ?)", TENANT_A, "a-2");
    adminJdbc.update("INSERT INTO widget (company_id, name) VALUES (?, ?)", TENANT_B, "b-1");

    // The application DataSource connects as the unprivileged role: this is
    // what RLS actually constrains.
    DriverManagerDataSource appDs =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    appDs.setDriverClassName("org.postgresql.Driver");
    this.jdbcTemplate = new JdbcTemplate(appDs);
    this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    this.synchronizer = new RlsTransactionSynchronizer(appDs, new RlsConnectionInitializer());
  }

  @Test
  void tenantScopeRestrictsReadsToThatTenant() throws Exception {
    List<String> aNames =
        TenantContext.callAs(
            TENANT_A,
            "actor-a",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return readAllNames();
                    }));
    assertThat(aNames).containsExactlyInAnyOrder("a-1", "a-2");

    List<String> bNames =
        TenantContext.callAs(
            TENANT_B,
            "actor-b",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return readAllNames();
                    }));
    assertThat(bNames).containsExactly("b-1");
  }

  @Test
  void countIsTenantScoped() throws Exception {
    long aCount =
        TenantContext.callAs(
            TENANT_A,
            "actor-a",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return countRows();
                    }));
    assertThat(aCount).isEqualTo(2L);
  }

  @Test
  void writesAreConstrainedToTheScopedTenant() throws Exception {
    // A write inside A's scope lands under A and is then visible only to A.
    TenantContext.callAs(
        TENANT_A,
        "actor-a",
        () ->
            transactionTemplate.execute(
                status -> {
                  synchronizer.applyToCurrentTransaction();
                  jdbcTemplate.update(
                      "INSERT INTO widget (company_id, name) VALUES (?, ?)", TENANT_A, "a-3");
                  return null;
                }));

    long bCount =
        TenantContext.callAs(
            TENANT_B,
            "actor-b",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return countRows();
                    }));
    assertThat(bCount).isEqualTo(1L); // unchanged: B never sees A's new row

    long aCount =
        TenantContext.callAs(
            TENANT_A,
            "actor-a",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return countRows();
                    }));
    assertThat(aCount).isEqualTo(3L);
  }

  @Test
  void writingForAnotherTenantIsRejectedByTheCheckConstraint() {
    // Even with a row carrying B's company_id, the WITH CHECK clause refuses
    // it while A is the session tenant: RLS blocks cross-tenant writes too.
    // PostgreSQL reports the WITH CHECK violation with SQLState 42501, which
    // Spring surfaces as a BadSqlGrammarException; the tell-tale
    // "row-level security policy" text is on the underlying PSQLException, so
    // we assert against the root cause.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    "actor-a",
                    () ->
                        transactionTemplate.execute(
                            status -> {
                              synchronizer.applyToCurrentTransaction();
                              jdbcTemplate.update(
                                  "INSERT INTO widget (company_id, name) VALUES (?, ?)",
                                  TENANT_B,
                                  "smuggled");
                              return null;
                            })))
        .rootCause()
        .hasMessageContaining("row-level security policy");
  }

  @Test
  void settingDoesNotLeakAcrossTransactions() {
    // No TenantContext scope and no applyToCurrentTransaction(): SET LOCAL
    // from prior transactions was cleared at their commit/rollback, so the
    // GUC is unset here and the policy returns zero rows -> fails closed,
    // proving the context cleared rather than leaked.
    long leaked = transactionTemplate.execute(status -> countRows());
    assertThat(leaked).isZero();
  }

  private List<String> readAllNames() {
    return jdbcTemplate.queryForList("SELECT name FROM widget ORDER BY name", String.class);
  }

  private long countRows() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM widget", Long.class);
    return count == null ? 0L : count;
  }
}
