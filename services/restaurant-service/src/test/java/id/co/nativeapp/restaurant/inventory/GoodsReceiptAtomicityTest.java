package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.messaging.StockReceivedSchema;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0067 Phase B (restaurant producer side): a PRICED {@code addStock} call (the {@code
 * Ingredient#receive} branch) writes exactly one {@code goods_receipt} row + one {@code
 * StockReceived} outbox row, with the exact {@code value_minor}/{@code currency}, in the SAME
 * transaction as the moving-average receive (rule 3) — and rolls everything back together on a
 * downstream failure (the {@code IngredientStocktakeAtomicityTest} pattern: a test-only {@link
 * OutboxWriter} performs the REAL insert and then throws). A NON-priced {@code addStock}/{@code
 * setStock} call is a costless adjustment and emits NEITHER row.
 */
@SpringBootTest
class GoodsReceiptAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-goods-receipt@example.co.id";
  private static final UUID OUTLET = UUID.fromString("77777777-7777-7777-7777-777777777777");

  static final String BOOM = "forced failure after outbox write (goods receipt atomicity)";

  @Autowired private IngredientService service;

  @Test
  void aPricedReceiveWritesOneGoodsReceiptAndOneStockReceivedOutboxRow() throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);

    TenantContext.callAs(TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR"));

    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(1L);

    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT ingredient_id, business_id, qty, value_minor, currency, idempotency_key,"
                    + " company_id"
                    + " FROM goods_receipt WHERE ingredient_id = ?")) {
      ps.setObject(1, ingredientId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject("ingredient_id", UUID.class)).isEqualTo(ingredientId);
        assertThat(rs.getObject("business_id", UUID.class)).isEqualTo(OUTLET);
        assertThat(rs.getLong("qty")).isEqualTo(10L);
        assertThat(rs.getLong("value_minor")).isEqualTo(130_000L);
        assertThat(rs.getString("currency").strip()).isEqualTo("IDR");
        assertThat(rs.getString("idempotency_key")).isNull();
        assertThat(rs.getString("company_id")).isEqualTo(TENANT);
      }
    }

    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT aggregate_type, event_type, payload, company_id FROM outbox")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("aggregate_type")).isEqualTo("goods_receipt");
      assertThat(rs.getString("event_type")).isEqualTo("StockReceived");
      assertThat(rs.getObject("company_id", UUID.class)).isEqualTo(UUID.fromString(TENANT));

      byte[] payload = rs.getBytes("payload");
      GenericRecord decoded = AvroSerde.deserialize(payload, StockReceivedSchema.schema());
      assertThat(decoded.get("ingredient_id").toString()).isEqualTo(ingredientId.toString());
      assertThat(decoded.get("qty")).isEqualTo(10L);
      assertThat(decoded.get("value_minor")).isEqualTo(130_000L);
      assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    }
  }

  @Test
  void aNonPricedAddStockEmitsNoGoodsReceiptOrOutboxRow() throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);

    TenantContext.callAs(TENANT, ACTOR, () -> service.addStock(ingredientId, 5, null, null));

    assertThat(rowCountAsAdmin("goods_receipt")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  @Autowired private ThrowingOutboxConfig.Holder throwingHolder;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackReceiveGoodsReceiptAndOutboxTogether() throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);
    throwingHolder.armed = true;
    try {
      assertThatThrownBy(
              () ->
                  TenantContext.callAs(
                      TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(BOOM);
    } finally {
      throwingHolder.armed = false;
    }

    // Everything rolled back together — nothing persists, and the ingredient was NOT re-valued.
    assertThat(rowCountAsAdmin("goods_receipt")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
    assertThat(stockOf(ingredientId)).isEqualTo(10);
    assertThat(stockValueOf(ingredientId)).isEqualTo(50_000L);
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private int stockOf(UUID ingredientId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private long stockValueOf(UUID ingredientId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT stock_value_minor FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private UUID seedIngredient(int stock, long unitCostMinor) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty,"
                    + " stock_value_minor, unit_cost_minor, cost_currency, active, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, 'Patty', 'pcs', ?, ?, ?, 'IDR', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET);
      ps.setInt(3, stock);
      ps.setLong(4, (long) stock * unitCostMinor);
      ps.setLong(5, unitCostMinor);
      ps.setString(6, TENANT);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * Installs an {@link OutboxWriter} that does the REAL insert and then throws — but only when
   * {@link ThrowingOutboxConfig.Holder#armed} is set, so the OTHER tests in this class (which share
   * the cached Spring context, {@code PostgresRlsTestBase}'s singleton-container pattern) still
   * commit normally. {@link Primary} so it wins injection into {@code IngredientWriter}.
   */
  @TestConfiguration
  static class ThrowingOutboxConfig {

    static class Holder {
      volatile boolean armed;
    }

    @Bean
    ThrowingOutboxConfig.Holder throwingOutboxHolder() {
      return new Holder();
    }

    @Bean
    @Primary
    OutboxWriter throwingOutboxWriter(JdbcTemplate jdbcTemplate, Holder holder) {
      return new OutboxWriter(jdbcTemplate) {
        @Override
        public UUID write(
            String aggregateType,
            String aggregateId,
            String eventType,
            byte[] payload,
            String headers,
            UUID companyId,
            Instant occurredAt) {
          UUID id =
              super.write(
                  aggregateType, aggregateId, eventType, payload, headers, companyId, occurredAt);
          if (holder.armed) {
            throw new IllegalStateException(BOOM);
          }
          return id;
        }
      };
    }
  }
}
