package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Atomicity (rule 3) for the ingredient stocktake write (ADR 0046 phase 1): the stock adjustment,
 * the {@code ingredient_stocktake} + {@code ingredient_stocktake_line} rows, and the {@code
 * StocktakeCompleted} outbox row must all commit together — or all roll back together. Mirrors
 * {@code StocktakeAtomicityTest}. A test-only {@link OutboxWriter} performs the REAL outbox insert
 * and then throws — still inside {@code IngredientStocktakeWriter#submit}'s single {@code
 * REQUIRES_NEW} transaction. Counts are taken over the admin (BYPASSRLS) connection because the
 * tables are FORCE RLS.
 */
@SpringBootTest
class IngredientStocktakeAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-ingredient-atomic@example.co.id";
  private static final UUID OUTLET = UUID.fromString("77777777-7777-7777-7777-777777777777");

  static final String BOOM = "forced failure after outbox write (ingredient stocktake atomicity)";

  @Autowired private IngredientStocktakeService service;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackStockAdjustStocktakeAndOutboxTogether()
      throws Exception {
    UUID ingredientId = seedIngredient(20, 7_500L);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        service.submit(
                            new SubmitIngredientStocktakeRequest(
                                OUTLET,
                                List.of(new IngredientStocktakeLineInput(ingredientId, 18))),
                            "atomic-ingredient-stocktake-key")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BOOM);

    // Everything rolled back together — nothing persists, and the stock was NOT adjusted.
    assertThat(rowCountAsAdmin("ingredient_stocktake")).isZero();
    assertThat(rowCountAsAdmin("ingredient_stocktake_line")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
    assertThat(stockOf(ingredientId)).isEqualTo(20);
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

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private UUID seedIngredient(int stock, long unitCostMinor) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, 'Patty', 'pcs', ?, ?, 'IDR', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET);
      ps.setInt(3, stock);
      ps.setLong(4, unitCostMinor);
      ps.setString(5, TENANT);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * Installs an {@link OutboxWriter} that does the real insert then throws. {@link Primary} so it
   * wins injection into {@code IngredientStocktakeWriter}; this test's context is distinct from
   * production and from the other {@code @SpringBootTest} classes, so the throwing writer never
   * leaks.
   */
  @TestConfiguration
  static class ThrowingOutboxConfig {

    @Bean
    @Primary
    OutboxWriter throwingOutboxWriter(JdbcTemplate jdbcTemplate) {
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
          throw new IllegalStateException(BOOM);
        }
      };
    }
  }
}
