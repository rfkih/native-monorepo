package id.co.nativeapp.restaurant.stocktake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;
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
 * Atomicity (rule 3) for the stocktake write (ADR 0038 phase 3): the stock adjustment, the {@code
 * stocktake} + {@code stocktake_line} rows, and the {@code StocktakeCompleted} outbox row must all
 * commit together — or all roll back together. A stocktake mutates money-relevant state (stock and
 * a shrinkage event finance posts from), so a torn write is a money bug.
 *
 * <p>Mirrors {@code CheckoutAtomicityTest}. A test-only {@link OutboxWriter} is installed that
 * performs the REAL outbox insert and then throws — still inside {@link
 * id.co.nativeapp.restaurant.stocktake.service.StocktakeWriter#submit}'s single {@code
 * REQUIRES_NEW} transaction. The failure must roll back the outbox row it just wrote, the two
 * stocktake tables, AND the menu item's stock adjustment — none survive. Counts are taken over the
 * admin (BYPASSRLS) connection because the tables are FORCE RLS.
 */
@SpringBootTest
class StocktakeAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-atomic@example.co.id";
  private static final UUID OUTLET = UUID.fromString("77777777-7777-7777-7777-777777777777");

  static final String BOOM = "forced failure after outbox write (stocktake atomicity test)";

  @Autowired private StocktakeService service;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackStockAdjustStocktakeAndOutboxTogether()
      throws Exception {
    UUID itemId = seedTrackedItem(20, 7_500L);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        service.submit(
                            new SubmitStocktakeRequest(
                                OUTLET, List.of(new StocktakeLineInput(itemId, 18))),
                            "atomic-stocktake-key")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BOOM);

    // Everything rolled back together — nothing persists, and the stock was NOT adjusted.
    assertThat(rowCountAsAdmin("stocktake")).isZero();
    assertThat(rowCountAsAdmin("stocktake_line")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
    assertThat(stockOf(itemId)).isEqualTo(20);
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private int stockOf(UUID itemId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private UUID seedTrackedItem(int stock, long unitCostMinor) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO menu_item (id, business_id, name, category, price_minor, currency,"
                    + " active, available, stock_quantity, unit_cost_minor, created_at, created_by,"
                    + " updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, 'Nasi Goreng', 'Food', 25000, 'IDR', true, true, ?, ?,"
                    + " now(), 'test', now(), 'test', 0, ?)")) {
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
   * wins injection into {@code StocktakeWriter}; this test's context is distinct from production
   * and from the other {@code @SpringBootTest} classes, so the throwing writer never leaks.
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
