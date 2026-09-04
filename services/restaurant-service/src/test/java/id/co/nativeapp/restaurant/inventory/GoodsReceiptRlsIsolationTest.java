package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Security + migration-application gate for V43's {@code goods_receipt} table (ADR 0067 Phase B
 * §1). No Java writer/entity exists yet (this migration ships ONLY the table + the policy — the
 * producer wiring in {@code IngredientWriter#addStock} is the next task), so this proves the policy
 * directly over raw JDBC, exactly {@link id.co.nativeapp.finance.InventoryMethodConfigRlsTest}'s
 * technique for {@code inventory_method_config} and {@link IngredientInventoryRlsIsolationTest}'s
 * technique for {@code ingredient}: bind {@code app.current_tenant} on a plain {@code app_user}
 * connection (no superuser BYPASSRLS) and show tenant B never sees tenant A's row.
 *
 * <p>Being a {@code @SpringBootTest}, the shared Testcontainers Postgres context only starts if
 * Flyway (including this class's V43) migrates cleanly — a failed {@code goods_receipt} migration
 * fails every test in the suite, so a green run here IS the "V43 applies cleanly" proof.
 *
 * <p>Also proves the §"IDEMPOTENCY KEY DECISION" partial unique index behaves as documented: two
 * NULL {@code idempotency_key} rows coexist (the column is not yet populated by any producer), a
 * duplicate key WITHIN a tenant is rejected, and the SAME key string reused by a DIFFERENT tenant
 * is allowed (the index is scoped by {@code (company_id, idempotency_key)}, not by key alone).
 */
@SpringBootTest
class GoodsReceiptRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "33333333-3333-3333-3333-333333333331";
  private static final String TENANT_B = "33333333-3333-3333-3333-333333333332";
  private static final UUID OUTLET_A = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @Test
  void tenantBCannotReadTenantAsGoodsReceiptAndCannotPlantOne() throws Exception {
    UUID ingredientId = seedIngredient(TENANT_A, OUTLET_A);
    UUID receiptId = seedGoodsReceipt(ingredientId, TENANT_A, OUTLET_A, null);

    assertThat(rowCount("goods_receipt", "id", receiptId, TENANT_A))
        .as("tenant A sees its own goods_receipt row under RLS")
        .isEqualTo(1L);
    assertThat(rowCount("goods_receipt", "id", receiptId, TENANT_B))
        .as("tenant B cannot read tenant A's goods_receipt row (FORCE RLS)")
        .isZero();

    // The admin (BYPASSRLS) connection proves the row genuinely persisted (not silently dropped).
    assertThat(countAsAdmin()).as("the row exists in the table").isEqualTo(1L);

    assertThatThrownBy(() -> plantGoodsReceiptAsWrongTenant(ingredientId))
        .as("WITH CHECK must reject a row stamped company_id = A while the session tenant is B")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    // The rejected insert must not have landed.
    assertThat(countAsAdmin()).isEqualTo(1L);
  }

  @Test
  void idempotencyKeyIsUniquePerTenantButNullsAndCrossTenantReuseAreBothAllowed() throws Exception {
    UUID ingredientId = seedIngredient(TENANT_A, OUTLET_A);

    // Two NULL-keyed rows coexist for the same tenant (no producer populates the column yet).
    seedGoodsReceipt(ingredientId, TENANT_A, OUTLET_A, null);
    seedGoodsReceipt(ingredientId, TENANT_A, OUTLET_A, null);
    assertThat(countAsAdmin())
        .as("NULL idempotency_key rows never collide under the partial unique index")
        .isEqualTo(2L);

    // A real key is unique WITHIN the tenant.
    seedGoodsReceipt(ingredientId, TENANT_A, OUTLET_A, "receive-key-1");
    assertThatThrownBy(() -> seedGoodsReceipt(ingredientId, TENANT_A, OUTLET_A, "receive-key-1"))
        .as("a duplicated idempotency_key for the SAME tenant must be rejected")
        .isInstanceOf(SQLException.class);

    // The SAME key string is a distinct receipt when it belongs to a DIFFERENT tenant (own
    // ingredient row — cross-tenant FK planting is exactly what RLS forbids elsewhere).
    UUID ingredientForB = seedIngredient(TENANT_B, OUTLET_A);
    seedGoodsReceipt(ingredientForB, TENANT_B, OUTLET_A, "receive-key-1");

    assertThat(countAsAdmin())
        .as("2 NULL rows + 1 keyed row for A + 1 same-keyed row for B")
        .isEqualTo(4L);
  }

  private long rowCount(String table, String column, UUID value, String tenant)
      throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + tenant + "'");
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM " + table + " WHERE " + column + " = '" + value + "'")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long countAsAdmin() throws SQLException {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM goods_receipt")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void plantGoodsReceiptAsWrongTenant(UUID ingredientId) throws SQLException {
    try (Connection app = appConnection();
        Statement set = app.createStatement()) {
      set.execute("SET app.current_tenant = '" + TENANT_B + "'");
      try (PreparedStatement ps =
          app.prepareStatement(
              "INSERT INTO goods_receipt (id, ingredient_id, business_id, qty, value_minor,"
                  + " currency, received_at, idempotency_key, created_at, created_by, updated_at,"
                  + " updated_by, version, company_id) VALUES (?, ?, ?, 10, 50000, 'IDR', now(),"
                  + " NULL, now(), 'attacker', now(), 'attacker', 0, ?)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, ingredientId);
        ps.setObject(3, OUTLET_A);
        ps.setString(4, TENANT_A);
        ps.executeUpdate();
      }
    }
  }

  /** Inserts one goods_receipt row bound to {@code tenant}, returning its id. */
  private UUID seedGoodsReceipt(
      UUID ingredientId, String tenant, UUID businessId, String idempotencyKey)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection app = appConnection();
        Statement set = app.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (PreparedStatement ps =
          app.prepareStatement(
              "INSERT INTO goods_receipt (id, ingredient_id, business_id, qty, value_minor,"
                  + " currency, received_at, idempotency_key, created_at, created_by, updated_at,"
                  + " updated_by, version, company_id) VALUES (?, ?, ?, 10, 50000, 'IDR', now(),"
                  + " ?, now(), 'test-actor', now(), 'test-actor', 0, ?)")) {
        ps.setObject(1, id);
        ps.setObject(2, ingredientId);
        ps.setObject(3, businessId);
        ps.setString(4, idempotencyKey);
        ps.setString(5, tenant);
        ps.executeUpdate();
      }
    }
    return id;
  }

  private UUID seedIngredient(String tenant, UUID businessId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, ?, 'pcs', 0, NULL, NULL, true, now(), 'test', now(), 'test',"
                    + " 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, businessId);
      ps.setString(3, "Goods Receipt Test Ingredient " + id);
      ps.setString(4, tenant);
      ps.executeUpdate();
    }
    return id;
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_secret");
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
