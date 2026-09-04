package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.messaging.InventoryPurchaseRecordedEvent;
import id.co.nativeapp.restaurant.inventory.service.InventoryPurchaseApplyService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0072 — the {@code InventoryPurchaseRecorded} consumer, service-layer against the real Flyway
 * migration + RLS (the fleet's consumer-acceptance pattern — no Kafka broker needed; the listener's
 * decode/DLT mechanics mirror {@code PaymentChargeExpiredListener} and are covered by the contract
 * test):
 *
 * <ul>
 *   <li>Each line becomes ONE priced goods receipt: stock + moving-average value move once, {@code
 *       goods_receipt.idempotency_key = line_id}, a {@code StockReceived} outbox row rides.
 *   <li>A re-delivered event (same event id) is a claimed no-op; a NEW event id carrying an
 *       already-received line replays per line off the goods_receipt anchor — nothing double-adds.
 *   <li>A bad line (unknown ingredient / currency mismatch) parks in the error inbox while the GOOD
 *       lines still apply — money is already posted in finance, so nothing may throw.
 *   <li>RLS: the consumer bound to tenant B cannot see tenant A's ingredient — it parks, never
 *       mutates cross-tenant.
 * </ul>
 */
@SpringBootTest
class InventoryPurchaseConsumerAcceptanceTest extends PostgresRlsTestBase {

  private static final Instant OCCURRED = Instant.parse("2026-09-03T02:00:00Z");

  @Autowired private InventoryPurchaseApplyService service;

  @Test
  void linesBecomeReceiptsOnceAcrossRedeliveryAndReplay() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = UUID.randomUUID();
    UUID ingredientA = seedIngredient(tenant, outlet, 10, 1_000L, "Ayam " + UUID.randomUUID());
    UUID ingredientB = seedIngredient(tenant, outlet, 0, 0L, "Cabai " + UUID.randomUUID());

    UUID purchaseId = UUID.randomUUID();
    UUID lineA = UUID.randomUUID();
    UUID lineB = UUID.randomUUID();
    InventoryPurchaseRecordedEvent event =
        new InventoryPurchaseRecordedEvent(
            UUID.randomUUID(),
            purchaseId,
            "EXPENSE",
            tenant,
            "IDR",
            OCCURRED,
            List.of(
                new InventoryPurchaseRecordedEvent.Line(lineA, ingredientA, 5L, 20_000L),
                new InventoryPurchaseRecordedEvent.Line(lineB, ingredientB, 8L, 16_000L)));

    assertThat(service.apply(event)).isTrue();

    // Stock + moving-average moved once per line: A had 10 pcs @1000 (value 10_000) + 5 for
    // 20_000 -> 15 pcs, value 30_000, avg 2000; B was uncosted with 0 stock -> 8 pcs @2000.
    assertThat(stockAsAdmin(ingredientA)).containsExactly(15L, 30_000L, 2_000L);
    assertThat(stockAsAdmin(ingredientB)).containsExactly(8L, 16_000L, 2_000L);
    assertThat(receiptKeyCountAsAdmin(lineA)).isEqualTo(1L);
    assertThat(receiptKeyCountAsAdmin(lineB)).isEqualTo(1L);
    assertThat(stockReceivedCountAsAdmin(tenant)).isEqualTo(2L);

    // Re-delivery (SAME event id): the processOnce claim skips everything.
    assertThat(service.apply(event)).isFalse();
    assertThat(stockAsAdmin(ingredientA)).containsExactly(15L, 30_000L, 2_000L);
    assertThat(stockReceivedCountAsAdmin(tenant)).isEqualTo(2L);

    // Replay (NEW event id, e.g. a finance-side backfill re-emit carrying the SAME lines): the
    // per-line goods_receipt anchor makes each line a no-op.
    InventoryPurchaseRecordedEvent replay =
        new InventoryPurchaseRecordedEvent(
            UUID.randomUUID(), purchaseId, "EXPENSE", tenant, "IDR", OCCURRED, event.lines());
    assertThat(service.apply(replay)).isTrue();
    assertThat(stockAsAdmin(ingredientA)).containsExactly(15L, 30_000L, 2_000L);
    assertThat(stockAsAdmin(ingredientB)).containsExactly(8L, 16_000L, 2_000L);
    assertThat(stockReceivedCountAsAdmin(tenant)).isEqualTo(2L);
    assertThat(errorCountAsAdmin(tenant)).isZero();
  }

  @Test
  void aBadLineParksWhileTheGoodLinesStillApply() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = UUID.randomUUID();
    UUID good = seedIngredient(tenant, outlet, 0, 0L, "Bawang " + UUID.randomUUID());
    UUID usdCosted = seedIngredientUsd(tenant, outlet, "Keju " + UUID.randomUUID());
    UUID unknown = UUID.randomUUID();

    InventoryPurchaseRecordedEvent event =
        new InventoryPurchaseRecordedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "BILL",
            tenant,
            "IDR",
            OCCURRED,
            List.of(
                new InventoryPurchaseRecordedEvent.Line(UUID.randomUUID(), unknown, 3L, 9_000L),
                new InventoryPurchaseRecordedEvent.Line(UUID.randomUUID(), usdCosted, 2L, 8_000L),
                new InventoryPurchaseRecordedEvent.Line(UUID.randomUUID(), good, 4L, 12_000L)));

    assertThat(service.apply(event)).isTrue();

    // The good line landed; the unknown-ingredient and currency-mismatch lines parked.
    assertThat(stockAsAdmin(good)).containsExactly(4L, 12_000L, 3_000L);
    assertThat(stockAsAdmin(usdCosted).get(0)).isZero();
    assertThat(stockReceivedCountAsAdmin(tenant)).isEqualTo(1L);
    assertThat(errorCountAsAdmin(tenant)).isEqualTo(2L);
  }

  @Test
  void theConsumerBoundToAnotherTenantCannotTouchForeignStock() throws Exception {
    String tenantA = UUID.randomUUID().toString();
    String tenantB = UUID.randomUUID().toString();
    UUID outlet = UUID.randomUUID();
    UUID ingredientOfA = seedIngredient(tenantA, outlet, 7, 500L, "Gula " + UUID.randomUUID());

    // The event claims tenant B but names tenant A's ingredient — RLS makes it invisible, so the
    // line parks under B and A's stock is untouched.
    InventoryPurchaseRecordedEvent event =
        new InventoryPurchaseRecordedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "EXPENSE",
            tenantB,
            "IDR",
            OCCURRED,
            List.of(
                new InventoryPurchaseRecordedEvent.Line(
                    UUID.randomUUID(), ingredientOfA, 5L, 10_000L)));

    assertThat(service.apply(event)).isTrue();
    assertThat(stockAsAdmin(ingredientOfA)).containsExactly(7L, 3_500L, 500L);
    assertThat(errorCountAsAdmin(tenantB)).isEqualTo(1L);
    assertThat(stockReceivedCountAsAdmin(tenantA)).isZero();
    assertThat(stockReceivedCountAsAdmin(tenantB)).isZero();
  }

  // ---------------------------------------------------------------- helpers

  private UUID seedIngredient(
      String tenant, UUID outlet, int stock, long unitCostMinor, String name) throws Exception {
    UUID id = UUID.randomUUID();
    long valueMinor = (long) stock * unitCostMinor;
    String costCurrency = unitCostMinor > 0 ? "IDR" : null;
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty,"
                    + " stock_value_minor, unit_cost_minor, cost_currency, active, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'pcs', ?, ?, ?, ?, true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, outlet);
      ps.setString(3, name);
      ps.setInt(4, stock);
      ps.setLong(5, valueMinor);
      ps.setObject(6, unitCostMinor > 0 ? unitCostMinor : null);
      ps.setString(7, costCurrency);
      ps.setString(8, tenant);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID seedIngredientUsd(String tenant, UUID outlet, String name) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty,"
                    + " stock_value_minor, unit_cost_minor, cost_currency, active, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'pcs', 0, 0, 100, 'USD', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, outlet);
      ps.setString(3, name);
      ps.setString(4, tenant);
      ps.executeUpdate();
    }
    return id;
  }

  /** {@code [stock_qty, stock_value_minor, unit_cost_minor]} for one ingredient, as admin. */
  private List<Long> stockAsAdmin(UUID ingredientId) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT stock_qty, stock_value_minor, COALESCE(unit_cost_minor, 0)"
                    + " FROM ingredient WHERE id = ?")) {
      ps.setObject(1, ingredientId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3));
      }
    }
  }

  private long receiptKeyCountAsAdmin(UUID lineId) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement("SELECT count(*) FROM goods_receipt WHERE idempotency_key = ?")) {
      ps.setString(1, lineId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long stockReceivedCountAsAdmin(String tenant) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM outbox WHERE event_type = 'StockReceived'"
                    + " AND company_id = ?::uuid")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long errorCountAsAdmin(String tenant) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM error_log WHERE company_id = ?"
                    + " AND source LIKE 'restaurant.inventory-purchase.%'")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
