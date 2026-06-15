package id.co.nativeapp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
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
 * P3d SEAM 2 — the TWO-GUC CONJUNCTION RLS proof against a real PostgreSQL 16, the libs/tenant
 * mirror of {@link RlsIsolationTest} for a GROUP table.
 *
 * <p>A {@code group_widget} table is created with the conjunction policy {@code group_id =
 * current_setting('app.current_group', true) AND company_id = current_setting('app.current_tenant',
 * true)} (USING + WITH CHECK), where {@code company_id} is the group's LEAD company. Work runs over
 * a separate connection authenticating as the unprivileged {@code app_user} (no BYPASSRLS), so the
 * policy is genuinely enforced; the binding flows through {@link RlsTransactionSynchronizer}
 * driving {@link RlsConnectionInitializer}.
 *
 * <p>The suite proves the binding state machine fails closed every way:
 *
 * <ol>
 *   <li>a GROUP binding ({@code tenant = lead, group = G}) reads the group's rows;
 *   <li>a plain single-tenant binding (the lead, NO group) reads NOTHING from the group table —
 *       {@code app.current_group} is unset so the group clause is false (the byte-identical
 *       single-tenant path never sees group rows);
 *   <li>a group binding for a DIFFERENT group sees NOTHING (group scope isolates);
 *   <li>a write under the group scope lands and is gated by the conjunction WITH CHECK — a write
 *       carrying a different {@code group_id} OR a different {@code company_id} is rejected;
 *   <li>group set but tenant cleared (and vice-versa) -> NOTHING (each partial binding fails
 *       closed);
 *   <li>no scope at all -> NOTHING (the GUCs cleared at the prior transaction's end, never leaked).
 * </ol>
 */
@Testcontainers
class GroupRlsConjunctionIsolationTest {

  private static final String LEAD = "lead-company";
  private static final String OTHER_LEAD = "other-lead-company";
  // Group ids are real (canonical) UUIDs: the single bind point RlsConnectionInitializer
  // .applyTenantAndGroup normalises/validates the group GUC to a canonical UUID (the type-exactness
  // anchor), so a non-UUID group id would (correctly) be rejected there.
  private static final String GROUP_G = "11111111-1111-1111-1111-111111111111";
  private static final String GROUP_H = "22222222-2222-2222-2222-222222222222";

  private static final String APP_USER = "grp_app_user";
  private static final String APP_PASSWORD = "app_secret";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private JdbcTemplate adminJdbc;
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

    adminJdbc.execute("DROP TABLE IF EXISTS group_widget");
    adminJdbc.execute(
        """
                DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_app_user') THEN
                        EXECUTE 'DROP OWNED BY grp_app_user CASCADE';
                        EXECUTE 'DROP ROLE grp_app_user';
                    END IF;
                END $$
                """);
    adminJdbc.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");

    adminJdbc.execute(
        """
                CREATE TABLE group_widget (
                    id         BIGSERIAL PRIMARY KEY,
                    group_id   TEXT NOT NULL,
                    company_id TEXT NOT NULL,
                    name       TEXT NOT NULL
                )
                """);
    adminJdbc.execute("ALTER TABLE group_widget ENABLE ROW LEVEL SECURITY");
    adminJdbc.execute("ALTER TABLE group_widget FORCE ROW LEVEL SECURITY");
    // THE conjunction policy: both the group scope AND the lead-company tenant must match.
    adminJdbc.execute(
        """
                CREATE POLICY group_widget_group_isolation ON group_widget
                    USING (group_id = current_setting('app.current_group', true)
                           AND company_id = current_setting('app.current_tenant', true))
                    WITH CHECK (group_id = current_setting('app.current_group', true)
                               AND company_id = current_setting('app.current_tenant', true))
                """);
    adminJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON group_widget TO " + APP_USER);
    adminJdbc.execute("GRANT USAGE, SELECT ON SEQUENCE group_widget_id_seq TO " + APP_USER);

    // Seed group G's rows (owned by LEAD) and a different group H's row, over the admin connection.
    adminJdbc.update(
        "INSERT INTO group_widget (group_id, company_id, name) VALUES (?, ?, ?)",
        GROUP_G,
        LEAD,
        "g-1");
    adminJdbc.update(
        "INSERT INTO group_widget (group_id, company_id, name) VALUES (?, ?, ?)",
        GROUP_G,
        LEAD,
        "g-2");
    adminJdbc.update(
        "INSERT INTO group_widget (group_id, company_id, name) VALUES (?, ?, ?)",
        GROUP_H,
        OTHER_LEAD,
        "h-1");

    DriverManagerDataSource appDs =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    appDs.setDriverClassName("org.postgresql.Driver");
    this.jdbcTemplate = new JdbcTemplate(appDs);
    this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    this.synchronizer = new RlsTransactionSynchronizer(appDs, new RlsConnectionInitializer());
  }

  @Test
  void groupBindingReadsTheGroupsRows() throws Exception {
    List<String> names =
        TenantContext.callAsGroup(
            LEAD,
            GROUP_G,
            "viewer",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return readNames();
                    }));
    assertThat(names).containsExactlyInAnyOrder("g-1", "g-2");
  }

  @Test
  void plainSingleTenantBindingSeesNothingInTheGroupTable() throws Exception {
    // The lead bound WITHOUT a group: app.current_group is never set, so the group clause is false
    // for every row -> the single-tenant path matches NOTHING in the group table (fail closed).
    long visible =
        TenantContext.callAs(
            LEAD,
            "viewer",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return countRows();
                    }));
    assertThat(visible).isZero();
  }

  @Test
  void aDifferentGroupSeesNothingOfGroupG() throws Exception {
    // H's lead+group sees only h-1, never G's two rows — the group scope isolates.
    List<String> visible =
        TenantContext.callAsGroup(
            OTHER_LEAD,
            GROUP_H,
            "viewer",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return readNames();
                    }));
    assertThat(visible).containsExactly("h-1");
  }

  @Test
  void writeUnderTheGroupScopeLandsAndIsVisibleOnlyInThatScope() throws Exception {
    TenantContext.callAsGroup(
        LEAD,
        GROUP_G,
        "writer",
        () ->
            transactionTemplate.execute(
                status -> {
                  synchronizer.applyToCurrentTransaction();
                  jdbcTemplate.update(
                      "INSERT INTO group_widget (group_id, company_id, name) VALUES (?, ?, ?)",
                      GROUP_G,
                      LEAD,
                      "g-3");
                  return null;
                }));

    long gCount =
        TenantContext.callAsGroup(
            LEAD,
            GROUP_G,
            "viewer",
            () ->
                transactionTemplate.execute(
                    status -> {
                      synchronizer.applyToCurrentTransaction();
                      return countRows();
                    }));
    assertThat(gCount).isEqualTo(3L);
  }

  @Test
  void writingForAnotherGroupIsRejectedByTheCheckConstraint() {
    // Bound to (LEAD, G) but inserting a row for group H: the conjunction WITH CHECK refuses it.
    assertThatThrownBy(
            () ->
                TenantContext.callAsGroup(
                    LEAD,
                    GROUP_G,
                    "writer",
                    () ->
                        transactionTemplate.execute(
                            status -> {
                              synchronizer.applyToCurrentTransaction();
                              jdbcTemplate.update(
                                  "INSERT INTO group_widget (group_id, company_id, name)"
                                      + " VALUES (?, ?, ?)",
                                  GROUP_H,
                                  LEAD,
                                  "smuggled-group");
                              return null;
                            })))
        .rootCause()
        .hasMessageContaining("row-level security policy");
  }

  @Test
  void writingForAnotherCompanyIsRejectedByTheCheckConstraint() {
    // Bound to (LEAD, G) but inserting a row whose company_id is a DIFFERENT company: the
    // company_id
    // half of the conjunction WITH CHECK refuses it — the group table stays
    // company_id-RLS-enforced.
    assertThatThrownBy(
            () ->
                TenantContext.callAsGroup(
                    LEAD,
                    GROUP_G,
                    "writer",
                    () ->
                        transactionTemplate.execute(
                            status -> {
                              synchronizer.applyToCurrentTransaction();
                              jdbcTemplate.update(
                                  "INSERT INTO group_widget (group_id, company_id, name)"
                                      + " VALUES (?, ?, ?)",
                                  GROUP_G,
                                  OTHER_LEAD,
                                  "smuggled-company");
                              return null;
                            })))
        .rootCause()
        .hasMessageContaining("row-level security policy");
  }

  @Test
  void groupSetButTenantUnsetSeesNothing() {
    // Manually set ONLY app.current_group (no tenant) on a raw app-role connection: the company_id
    // half of the conjunction is false -> fail closed.
    long visible =
        transactionTemplate.execute(
            status -> {
              jdbcTemplate.execute(
                  "SELECT set_config('app.current_group', '" + GROUP_G + "', true)");
              return countRows();
            });
    assertThat(visible).isZero();
  }

  @Test
  void tenantSetButGroupUnsetSeesNothing() {
    // ONLY app.current_tenant (the lead), no group -> the group clause is false -> fail closed.
    long visible =
        transactionTemplate.execute(
            status -> {
              jdbcTemplate.execute("SELECT set_config('app.current_tenant', '" + LEAD + "', true)");
              return countRows();
            });
    assertThat(visible).isZero();
  }

  @Test
  void settingsDoNotLeakAcrossTransactions() {
    // No scope at all: SET LOCAL from prior transactions cleared at commit/rollback, so both GUCs
    // are
    // unset and the conjunction matches nothing -> fail closed, proving the context cleared.
    long leaked = transactionTemplate.execute(status -> countRows());
    assertThat(leaked).isZero();
  }

  @Test
  void groupGucDoesNotLeakFromAPriorGroupTxnIntoALaterSingleTenantTxnOnTheSameConnection()
      throws Exception {
    // LEAK-RESIDUE REGRESSION (P3d SEAM 2, libs/tenant blast radius). settingsDoNotLeakAcrossTxns
    // above starts from a CLEAN connection, so it never exercises the
    // prior-GROUP-then-single-tenant
    // residue path. This one does: it pins a ONE-connection HikariCP pool so transaction N and N+1
    // PROVABLY reuse the SAME physical connection, then proves the new app.current_group GUC set by
    // a GROUP binding does NOT survive into a later PLAIN single-tenant binding on that connection.
    HikariDataSource pooled = new HikariDataSource();
    pooled.setJdbcUrl(POSTGRES.getJdbcUrl());
    pooled.setUsername(APP_USER);
    pooled.setPassword(APP_PASSWORD);
    pooled.setDriverClassName("org.postgresql.Driver");
    pooled.setMaximumPoolSize(1); // force physical-connection REUSE across the two transactions
    pooled.setMinimumIdle(1);
    try {
      JdbcTemplate pooledJdbc = new JdbcTemplate(pooled);
      TransactionTemplate pooledTx =
          new TransactionTemplate(new DataSourceTransactionManager(pooled));
      RlsTransactionSynchronizer pooledSync =
          new RlsTransactionSynchronizer(pooled, new RlsConnectionInitializer());

      // Capture the backend PID so we can ASSERT both transactions ran on the same physical
      // connection (otherwise this would not actually test the residue path).
      int pidN =
          TenantContext.callAsGroup(
              LEAD,
              GROUP_G,
              "group-writer",
              () ->
                  pooledTx.execute(
                      status -> {
                        // Transaction N — GROUP binding: sets BOTH app.current_tenant AND
                        // app.current_group via SET LOCAL on this connection.
                        pooledSync.applyToCurrentTransaction();
                        // Sanity: inside N the group IS visible (both GUCs set, conjunction holds).
                        assertThat(countRows(pooledJdbc)).isEqualTo(2L);
                        assertThat(currentGroupGuc(pooledJdbc)).isEqualTo(GROUP_G);
                        return backendPid(pooledJdbc);
                      }));

      // Transaction N+1 — PLAIN single-tenant binding (callAs, NO group) on the SAME pooled
      // connection. applyToCurrentTransaction sets ONLY app.current_tenant; it never touches
      // app.current_group.
      int pidNplus1 =
          TenantContext.callAs(
              LEAD,
              "single-tenant-reader",
              () ->
                  pooledTx.execute(
                      status -> {
                        pooledSync.applyToCurrentTransaction();
                        // (a) reads 0 group rows — app.current_group reset at N's commit, so the
                        // group clause is NULL -> excluded (the byte-identical single-tenant path).
                        assertThat(countRows(pooledJdbc)).isZero();
                        // (b) app.current_group IS NULL inside N+1 — proving SET LOCAL auto-reset
                        // at
                        // N's commit AND that the single-tenant path never re-sets the group GUC.
                        assertThat(currentGroupGuc(pooledJdbc)).isNull();
                        return backendPid(pooledJdbc);
                      }));

      // The residue path is only exercised if it was the SAME physical connection both times.
      assertThat(pidNplus1).isEqualTo(pidN);
    } finally {
      pooled.close();
    }
  }

  private List<String> readNames() {
    return jdbcTemplate.queryForList("SELECT name FROM group_widget ORDER BY name", String.class);
  }

  private long countRows() {
    return countRows(jdbcTemplate);
  }

  private static long countRows(JdbcTemplate jdbc) {
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM group_widget", Long.class);
    return count == null ? 0L : count;
  }

  /**
   * The current value of the app.current_group GUC ({@code null} when unset), read with {@code
   * missing_ok = true} so an undefined GUC returns NULL instead of erroring. Postgres returns an
   * empty string for a set-then-reset custom GUC; normalise that to {@code null} too.
   */
  private static String currentGroupGuc(JdbcTemplate jdbc) {
    String value =
        jdbc.queryForObject(
            "SELECT current_setting('" + RlsConnectionInitializer.GROUP_SETTING + "', true)",
            String.class);
    return (value == null || value.isEmpty()) ? null : value;
  }

  /** The PostgreSQL backend PID of the connection this transaction is bound to. */
  private static int backendPid(JdbcTemplate jdbc) {
    Integer pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    return pid == null ? -1 : pid;
  }
}
