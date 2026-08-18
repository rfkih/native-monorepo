package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Migration test for restaurant-service V44 ({@code sale.cogs_minor} / {@code sale.cogs_currency} —
 * ADR 0067 Phase C, §1 "SaleCogsRecorded"). Reproduces the real Flyway sequence exactly like {@link
 * IngredientDisplayUnitBackfillMigrationTest}: migrate to the pre-V44 baseline (V43) as the
 * unprivileged, table-owning {@code app_user} role, plant a sale row shaped exactly like a
 * pre-Phase-C production row, migrate to latest, and assert:
 *
 * <ol>
 *   <li>the migration applies cleanly and every pre-existing column on the pre-existing row is
 *       preserved byte-for-byte (nothing "expand" ever rewrites);
 *   <li>the two new columns default NULL on that old row and on a freshly-inserted row that omits
 *       them (nothing reads or requires them yet — this is a purely additive column add, V33's
 *       {@code sold_by_user_id} precedent);
 *   <li>the new {@code ck_sale_cogs_both_or_neither} CHECK rejects a half-populated COGS pair and
 *       accepts a fully-populated one;
 *   <li>{@code sale}'s RLS (ENABLE + FORCE) survives the ALTER TABLE untouched;
 *   <li>the migration's documented compensating DDL (drop the two columns + the CHECK) rolls the
 *       table back to its exact pre-V44 shape — the "reversible" half of the expand/contract plan
 *       (Flyway OSS ships no automatic undo, so the compensating DDL is the reversibility proof).
 * </ol>
 */
class SaleCogsMigrationTest {

  private static final String DB = "sale_cogs_migration_test";
  private static final String TENANT = "sale-cogs-tenant-a";

  @Test
  void v44AddsNullableCogsColumnsPreservingExistingSaleRowsByteForByte() throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES; // starts container + app_user role
    String testDbUrl =
        "jdbc:postgresql://"
            + pg.getHost()
            + ":"
            + pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + DB;

    // Fresh database for this test only.
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

    // 1) Migrate to V43 (the pre-V44 baseline) as the unprivileged owner role — exactly how
    //    Flyway runs in production. `sale` exists with NO cogs columns yet.
    flyway(testDbUrl).target("43").load().migrate();

    // 2) Plant a pre-Phase-C sale row over the admin (BYPASSRLS) connection, shaped exactly like
    //    production data today.
    UUID saleId = seedSale(testDbUrl, TENANT, 150_000L, "IDR");

    // 3) Migrate to latest (V44) as the owner role, under sale's real FORCE ROW LEVEL SECURITY.
    flyway(testDbUrl).load().migrate();

    // 4) The old row's original columns are untouched, and the two new columns default NULL.
    assertSaleRow(testDbUrl, saleId, 150_000L, "IDR", null, null);

    // 5) A freshly-inserted sale that omits the new columns also defaults NULL — nothing
    //    downstream is required to populate them (the producer-wiring task is separate).
    UUID freshSaleId = seedSale(testDbUrl, TENANT, 42_000L, "IDR");
    assertSaleRow(testDbUrl, freshSaleId, 42_000L, "IDR", null, null);

    // 6) A fully-populated COGS pair is accepted...
    setCogs(testDbUrl, freshSaleId, 18_500L, "IDR");
    assertSaleRow(testDbUrl, freshSaleId, 42_000L, "IDR", 18_500L, "IDR");

    // ...but a half-populated pair (amount without currency) is rejected by
    // ck_sale_cogs_both_or_neither — the both-or-neither guard mirrors
    // ck_ingredient_cost_both_or_neither (V31).
    assertThatThrownBy(() -> setCogsMinorOnly(testDbUrl, freshSaleId, 9_000L))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_sale_cogs_both_or_neither");

    // 7) RLS survives the ALTER TABLE untouched.
    assertForceRowLevelSecurityRestored(testDbUrl);

    // 8) Reversibility: the documented compensating DDL (an explicit DROP, since Flyway OSS has
    //    no automatic undo) cleanly returns `sale` to its exact pre-V44 shape — no orphaned
    //    constraint, no data loss on the original columns, and the table remains fully usable
    //    (insert without the dropped columns still succeeds).
    rollbackV44(testDbUrl);
    assertColumnsAbsent(testDbUrl);
    UUID postRollbackSaleId = seedSale(testDbUrl, TENANT, 7_000L, "USD");
    assertThat(saleAmountAndCurrency(testDbUrl, postRollbackSaleId)).isEqualTo("7000/USD");
    assertForceRowLevelSecurityRestored(testDbUrl);
  }

  // ----------------------------------------------------------------------- helpers

  private static FluentConfiguration flyway(String url) {
    return Flyway.configure()
        .dataSource(url, PostgresRlsTestBase.APP_USER, PostgresRlsTestBase.APP_PASSWORD)
        .locations("classpath:db/migration");
  }

  private UUID seedSale(String dbUrl, String tenant, long amountMinor, String currency)
      throws Exception {
    UUID id = UUID.randomUUID();
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO sale (id, business_id, amount_minor, currency, occurred_at,"
                    + " idempotency_key, created_at, created_by, updated_at, updated_by, version,"
                    + " company_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, now(), 'mig-test', now(), 'mig-test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, UUID.randomUUID());
      ps.setLong(3, amountMinor);
      ps.setString(4, currency);
      ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-06-14T08:30:00Z")));
      ps.setString(6, "seed-" + id);
      ps.setString(7, tenant);
      ps.executeUpdate();
    }
    return id;
  }

  private void setCogs(String dbUrl, UUID saleId, long cogsMinor, String currency)
      throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE sale SET cogs_minor = ?, cogs_currency = ? WHERE id = ?")) {
      ps.setLong(1, cogsMinor);
      ps.setString(2, currency);
      ps.setObject(3, saleId);
      ps.executeUpdate();
    }
  }

  private void setCogsMinorOnly(String dbUrl, UUID saleId, long cogsMinor) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE sale SET cogs_minor = ?, cogs_currency = NULL WHERE id = ?")) {
      ps.setLong(1, cogsMinor);
      ps.setObject(2, saleId);
      ps.executeUpdate();
    }
  }

  private void assertSaleRow(
      String dbUrl,
      UUID id,
      long expectedAmountMinor,
      String expectedCurrency,
      Long expectedCogsMinor,
      String expectedCogsCurrency)
      throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT amount_minor, currency, cogs_minor, cogs_currency FROM sale WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("row %s exists", id).isTrue();
        assertThat(rs.getLong("amount_minor"))
            .as("amount_minor for %s must be preserved byte-for-byte", id)
            .isEqualTo(expectedAmountMinor);
        assertThat(rs.getString("currency")).as("currency for %s", id).isEqualTo(expectedCurrency);

        long cogsMinor = rs.getLong("cogs_minor");
        boolean cogsMinorWasNull = rs.wasNull();
        if (expectedCogsMinor == null) {
          assertThat(cogsMinorWasNull).as("cogs_minor for %s should be NULL", id).isTrue();
        } else {
          assertThat(cogsMinorWasNull).as("cogs_minor for %s should NOT be NULL", id).isFalse();
          assertThat(cogsMinor).as("cogs_minor for %s", id).isEqualTo(expectedCogsMinor);
        }

        String cogsCurrency = rs.getString("cogs_currency");
        assertThat(cogsCurrency).as("cogs_currency for %s", id).isEqualTo(expectedCogsCurrency);
      }
    }
  }

  private String saleAmountAndCurrency(String dbUrl, UUID id) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT amount_minor, currency FROM sale WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getLong("amount_minor") + "/" + rs.getString("currency");
      }
    }
  }

  private void assertForceRowLevelSecurityRestored(String dbUrl) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class"
                    + " WHERE relname = 'sale'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean("relrowsecurity")).as("RLS enabled").isTrue();
      assertThat(rs.getBoolean("relforcerowsecurity")).as("RLS forced (owner included)").isTrue();
    }
  }

  /**
   * The documented reversible/compensating half of the expand: an explicit contract migration that
   * drops the CHECK then the two columns. Flyway Community has no built-in "undo" — this is what a
   * human-approved rollback plan for this purely additive change would run.
   */
  private void rollbackV44(String dbUrl) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("ALTER TABLE sale DROP CONSTRAINT IF EXISTS ck_sale_cogs_both_or_neither");
      st.execute("ALTER TABLE sale DROP COLUMN IF EXISTS cogs_minor");
      st.execute("ALTER TABLE sale DROP COLUMN IF EXISTS cogs_currency");
    }
  }

  private void assertColumnsAbsent(String dbUrl) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'sale' AND column_name IN"
                    + " ('cogs_minor', 'cogs_currency')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).as("cogs columns should be gone after rollback").isZero();
    }
  }
}
