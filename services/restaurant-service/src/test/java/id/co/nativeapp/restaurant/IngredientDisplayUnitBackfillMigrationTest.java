package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Regression pin for the V37 {@code ingredient.display_unit} backfill's code-review C1 fix: a
 * COSTED kg/liter row with ZERO stock (created with a cost + {@code initialStockQty=0}, or depleted
 * to empty) retains its last-known PER-DISPLAY-UNIT {@code unit_cost_minor} — V36's zero-stock
 * invariant ({@code ck_ingredient_value_requires_stock}) forces {@code stock_value_minor = 0} for
 * it, so the cache cannot be re-derived from value. The ORIGINAL CASE's {@code ELSE
 * unit_cost_minor} left that cache untouched while {@code unit} flipped kg-&gt;g / liter-&gt;ml — a
 * silent 1000x cache that the next costless restock ({@code applyCostlessQuantity}'s from-empty
 * branch: {@code newQty * unitCostMinor}) would bake straight into {@code stock_value_minor}, the
 * money source of truth (rule 8). The fix rescales the retained per-display-unit cache down by the
 * fixed 1000 factor in the zero-stock {@code ELSE} branch too, instead of leaving it.
 *
 * <p>Reproduces the exact real-world Flyway sequence the acceptance tests cannot (they only see
 * rows created AFTER the full migration chain has already run): migrate to V36 (no {@code
 * display_unit} column yet, {@code unit} still whole-kg/whole-liter per V31), plant pre-V37-shape
 * rows over the admin (superuser, bypasses RLS) connection, then migrate to latest as the
 * unprivileged, table-owning {@code app_user} role — exactly how Flyway runs in production, under
 * {@code ingredient}'s real {@code FORCE ROW LEVEL SECURITY} (V31) — and assert the backfill
 * result. Mirrors the fleet precedent {@code VerticalBackfillMigrationTest} (org-service); lives in
 * this top-level package (not nested under {@code inventory}) because it needs package access to
 * {@link PostgresRlsTestBase}'s protected {@code POSTGRES} container and package-private {@code
 * APP_USER}/{@code APP_PASSWORD} — the same reason the org-service precedent isn't nested either.
 */
class IngredientDisplayUnitBackfillMigrationTest {

  private static final String DB = "ingredient_display_unit_backfill_test";
  private static final String TENANT_A = "display-unit-tenant-a";
  private static final String TENANT_B = "display-unit-tenant-b";

  @Test
  void backfillPreservesValueFlipsUnitsAndRescalesTheZeroStockCostedCache() throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES; // starts container + app_user role
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

    // 1) Migrate to V36 as the unprivileged owner role (how Flyway runs for real): ingredient
    //    exists with unit_cost_minor + stock_value_minor but NO display_unit column yet.
    flyway(testDbUrl).target("36").load().migrate();

    // 2) Plant pre-V37-shape rows over the admin (BYPASSRLS) connection — two tenants, so the
    //    assertion also pins that the backfill reaches every tenant despite ingredient's FORCE RLS.
    UUID kgNormal = seedCosted(testDbUrl, TENANT_A, "kg", 5, 12_345L, 61_225L);
    // THE FLAGGED SHAPE (C1): costed kg, ZERO stock — value is 0 (V36 invariant), cache retains
    // the stale per-KG figure until the backfill rescales it.
    UUID kgZeroStockCosted = seedCosted(testDbUrl, TENANT_A, "kg", 0, 12_000L, 0L);
    UUID kgUncosted = seedUncosted(testDbUrl, TENANT_A, "kg", 4);
    UUID literNormal = seedCosted(testDbUrl, TENANT_B, "liter", 2, 8_000L, 17_000L);
    UUID pcsUntouched = seedCosted(testDbUrl, TENANT_A, "pcs", 10, 500L, 5_000L);
    UUID gAlreadyBase = seedCosted(testDbUrl, TENANT_B, "g", 250, 10L, 2_500L);

    // 3) Migrate to latest (V37) as the owner role, under ingredient's real FORCE ROW LEVEL
    //    SECURITY — without V37's NO FORCE escape hatch the backfill UPDATE would silently match
    //    zero rows in every tenant (the same trap V36/org-service-V7/finance-service-V12 hit).
    flyway(testDbUrl).load().migrate();

    // 4) Assert — value preserved byte-for-byte on every row, unit/display_unit flip only for
    //    kg/liter rows, and the cache is either correctly re-derived (qty > 0) or correctly
    //    rescaled (the zero-stock costed row — the C1 regression).
    assertRow(testDbUrl, kgNormal, "g", "kg", 5000, 12L, 61_225L);
    assertRow(
        testDbUrl, kgZeroStockCosted, "g", "kg", 0, 12L, 0L); // 12000 -> 12, NOT left at 12000
    assertRow(testDbUrl, kgUncosted, "g", "kg", 4000, null, 0L);
    assertRow(testDbUrl, literNormal, "ml", "liter", 2000, 9L, 17_000L);
    assertRow(testDbUrl, pcsUntouched, "pcs", null, 10, 500L, 5_000L);
    assertRow(testDbUrl, gAlreadyBase, "g", null, 250, 10L, 2_500L);

    assertForceRowLevelSecurityRestored(testDbUrl);
    assertNoCheckConstraintViolations(testDbUrl);
  }

  // ----------------------------------------------------------------------- helpers

  private static FluentConfiguration flyway(String url) {
    return Flyway.configure()
        .dataSource(url, PostgresRlsTestBase.APP_USER, PostgresRlsTestBase.APP_PASSWORD)
        .locations("classpath:db/migration");
  }

  private UUID seedCosted(
      String dbUrl, String tenant, String unit, int stockQty, long unitCostMinor, long valueMinor)
      throws Exception {
    UUID id = UUID.randomUUID();
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id, stock_value_minor)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'IDR', true, now(), 'mig-test', now(),"
                    + " 'mig-test', 0, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, UUID.randomUUID());
      ps.setString(3, "seed-" + unit + "-" + id);
      ps.setString(4, unit);
      ps.setInt(5, stockQty);
      ps.setLong(6, unitCostMinor);
      ps.setString(7, tenant);
      ps.setLong(8, valueMinor);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID seedUncosted(String dbUrl, String tenant, String unit, int stockQty)
      throws Exception {
    UUID id = UUID.randomUUID();
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, active,"
                    + " created_at, created_by, updated_at, updated_by, version, company_id,"
                    + " stock_value_minor)"
                    + " VALUES (?, ?, ?, ?, ?, true, now(), 'mig-test', now(), 'mig-test', 0, ?,"
                    + " 0)")) {
      ps.setObject(1, id);
      ps.setObject(2, UUID.randomUUID());
      ps.setString(3, "seed-" + unit + "-" + id);
      ps.setString(4, unit);
      ps.setInt(5, stockQty);
      ps.setString(6, tenant);
      ps.executeUpdate();
    }
    return id;
  }

  private void assertRow(
      String dbUrl,
      UUID id,
      String expectedUnit,
      String expectedDisplayUnit,
      int expectedStockQty,
      Long expectedUnitCostMinor,
      long expectedStockValueMinor)
      throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT unit, display_unit, stock_qty, unit_cost_minor, stock_value_minor"
                    + " FROM ingredient WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("row %s exists", id).isTrue();
        assertThat(rs.getString("unit")).as("unit for %s", id).isEqualTo(expectedUnit);
        assertThat(rs.getString("display_unit"))
            .as("display_unit for %s", id)
            .isEqualTo(expectedDisplayUnit);
        assertThat(rs.getInt("stock_qty")).as("stock_qty for %s", id).isEqualTo(expectedStockQty);
        long unitCostMinor = rs.getLong("unit_cost_minor");
        boolean unitCostWasNull = rs.wasNull();
        if (expectedUnitCostMinor == null) {
          assertThat(unitCostWasNull).as("unit_cost_minor for %s should be NULL", id).isTrue();
        } else {
          assertThat(unitCostWasNull).as("unit_cost_minor for %s should NOT be NULL", id).isFalse();
          assertThat(unitCostMinor)
              .as("unit_cost_minor for %s", id)
              .isEqualTo(expectedUnitCostMinor);
        }
        assertThat(rs.getLong("stock_value_minor"))
            .as("stock_value_minor for %s must be preserved byte-for-byte", id)
            .isEqualTo(expectedStockValueMinor);
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
                    + " WHERE relname = 'ingredient'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean("relrowsecurity")).as("RLS enabled").isTrue();
      assertThat(rs.getBoolean("relforcerowsecurity")).as("RLS forced (owner included)").isTrue();
    }
  }

  /**
   * Re-checks every V36 CHECK ({@code ck_ingredient_stock_nonneg}, {@code
   * ck_ingredient_cost_both_or_neither}, {@code ck_ingredient_stock_value_nonneg}, {@code
   * ck_ingredient_value_requires_cost}, {@code ck_ingredient_value_requires_stock}) plus the new
   * V37 {@code ck_ingredient_display_unit_pairing} against the post-backfill data — belt-and-braces
   * on top of Postgres itself refusing to commit a violating row.
   */
  private void assertNoCheckConstraintViolations(String dbUrl) throws Exception {
    PostgreSQLContainer<?> pg = PostgresRlsTestBase.POSTGRES;
    try (Connection admin = DriverManager.getConnection(dbUrl, pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM ingredient WHERE"
                    + " NOT (stock_qty >= 0)"
                    + " OR NOT ((unit_cost_minor IS NULL) = (cost_currency IS NULL))"
                    + " OR NOT (stock_value_minor >= 0)"
                    + " OR NOT (stock_value_minor = 0 OR cost_currency IS NOT NULL)"
                    + " OR NOT (stock_qty > 0 OR stock_value_minor = 0)"
                    + " OR NOT (display_unit IS NULL"
                    + "         OR (display_unit = 'kg' AND unit = 'g')"
                    + "         OR (display_unit = 'liter' AND unit = 'ml'))")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).as("rows violating any ingredient CHECK constraint").isZero();
    }
  }
}
