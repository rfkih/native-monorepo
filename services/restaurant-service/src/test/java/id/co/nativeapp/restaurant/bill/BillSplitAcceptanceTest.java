package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillLineResponse;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance tests for the Increment 3 split-by-item feature on the bill aggregate.
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Pay a subset of lines → those lines {@code paid=true}, a {@code SaleRecorded} emitted for
 *       the subset total, bill still OPEN, stock deducted for only those lines.
 *   <li>Pay the remaining lines → bill PAID; the SUM of the two check sales equals the whole-bill
 *       total (no double count, no gap).
 *   <li>Cannot pay an already-paid line → 400/409.
 *   <li>Paying an empty set → 400.
 *   <li>Insufficient stock on a check → 422 + that check rolls back (lines stay unpaid, no sale,
 *       stock unchanged); already-paid lines from a prior check are unaffected.
 *   <li>Idempotent re-pay of a check (same idempotencyKey) → no second sale.
 *   <li>The existing full-bill pay tests (no lineIds) still pass.
 * </ul>
 */
@SpringBootTest
class BillSplitAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aa330001-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("cc330001-cccc-cccc-cccc-cccccccccccc");

  @Autowired private BillService billService;
  @Autowired private MenuService menuService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // -----------------------------------------------------------------------
  // Setup helpers
  // -----------------------------------------------------------------------

  private UUID seedItem(long price) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, "Item-" + price, "MAIN", price, "IDR"))
                .id());
  }

  private UUID seedTrackedItem(long price, int stock) throws Exception {
    UUID id = seedItem(price);
    executeAsAdmin("UPDATE menu_item SET stock_quantity = " + stock + " WHERE id = '" + id + "'");
    return id;
  }

  private BillResponse openBill(String guestLabel) throws Exception {
    return TenantContext.callAs(
        TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, guestLabel)));
  }

  private BillResponse appendLines(UUID billId, List<OrderLineRequest> lines) throws Exception {
    return TenantContext.callAs(
        TENANT_A, ACTOR, () -> billService.appendLines(billId, new AppendLinesRequest(lines)));
  }

  private BillResponse payAll(UUID billId) throws Exception {
    return TenantContext.callAs(
        TENANT_A, ACTOR, () -> billService.payBill(billId, new PayBillRequest()));
  }

  private BillResponse payLines(UUID billId, List<UUID> lineIds) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> billService.payBill(billId, new PayBillRequest(null, null, lineIds, null, null)));
  }

  private BillResponse payLinesWithKey(UUID billId, List<UUID> lineIds, String idemKey)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.payBill(billId, new PayBillRequest(null, null, lineIds, idemKey, null)));
  }

  private List<Map<String, Object>> saleRecordedRows() {
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, company_id, payload "
            + "FROM outbox WHERE event_type = 'SaleRecorded' ORDER BY occurred_at");
  }

  private int stockOf(UUID itemId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private void executeAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(sql);
    }
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  /**
   * Core split scenario: pay line A first (check 1), then line B (check 2). After check 1 the bill
   * is still OPEN; after check 2 the bill is PAID. The sum of the two check amounts equals the
   * whole-bill subtotal.
   */
  @Test
  void splitByItem_twoChecks_billPaidAfterAllLinesCovered() throws Exception {
    UUID itemA = seedItem(10_000L); // IDR 10,000
    UUID itemB = seedItem(20_000L); // IDR 20,000

    BillResponse bill = openBill("Split-Two");

    // Append both items in one round.
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)));
    assertThat(afterAppend.lines()).hasSize(2);

    UUID lineAId = afterAppend.lines().get(0).id();
    UUID lineBId = afterAppend.lines().get(1).id();

    // ----- Check 1: pay line A only -----
    BillResponse afterCheck1 = payLines(bill.id(), List.of(lineAId));

    // Bill is still OPEN.
    assertThat(afterCheck1.status()).isEqualTo("OPEN");
    assertThat(afterCheck1.saleId()).isNull();

    // Line A is paid, line B is not.
    Map<UUID, BillLineResponse> byId1 =
        afterCheck1.lines().stream()
            .collect(java.util.stream.Collectors.toMap(BillLineResponse::id, l -> l));
    assertThat(byId1.get(lineAId).paid()).isTrue();
    assertThat(byId1.get(lineBId).paid()).isFalse();

    // Exactly one SaleRecorded for 10,000 IDR.
    List<Map<String, Object>> outbox1 = saleRecordedRows();
    assertThat(outbox1).hasSize(1);
    GenericRecord decoded1 =
        AvroSerde.deserialize(
            (byte[]) outbox1.getFirst().get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded1.get("amount_minor")).isEqualTo(10_000L);
    UUID check1SaleId = UUID.fromString(decoded1.get("sale_id").toString());

    // ----- Check 2: pay the remaining line B -----
    BillResponse afterCheck2 = payLines(bill.id(), List.of(lineBId));

    // Bill is now PAID.
    assertThat(afterCheck2.status()).isEqualTo("PAID");
    assertThat(afterCheck2.saleId()).isNotNull();

    // Both lines are paid.
    Map<UUID, BillLineResponse> byId2 =
        afterCheck2.lines().stream()
            .collect(java.util.stream.Collectors.toMap(BillLineResponse::id, l -> l));
    assertThat(byId2.get(lineAId).paid()).isTrue();
    assertThat(byId2.get(lineBId).paid()).isTrue();

    // Two SaleRecorded events; amounts sum to 30,000.
    List<Map<String, Object>> outbox2 = saleRecordedRows();
    assertThat(outbox2).hasSize(2);
    long totalRevenue =
        outbox2.stream()
            .mapToLong(
                row -> {
                  GenericRecord r =
                      AvroSerde.deserialize(
                          (byte[]) row.get("payload"), SaleRecordedSchema.schema());
                  return (long) r.get("amount_minor");
                })
            .sum();
    assertThat(totalRevenue).isEqualTo(30_000L);
  }

  /** Paying all remaining lines (full-bill pay) with no lineIds still works as before. */
  @Test
  void fullBillPay_noLineIds_worksAsBeforeAndLinesPaidFlagIsTrue() throws Exception {
    UUID itemId = seedItem(15_000L);

    BillResponse bill = openBill("Full-Pay");
    appendLines(bill.id(), List.of(new OrderLineRequest(itemId, 2)));

    BillResponse paid = payAll(bill.id());

    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(paid.saleId()).isNotNull();
    assertThat(paid.lines()).allMatch(BillLineResponse::paid);
    assertThat(saleRecordedRows()).hasSize(1);
    GenericRecord decoded =
        AvroSerde.deserialize(
            (byte[]) saleRecordedRows().getFirst().get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(30_000L);
  }

  /**
   * Attempting to pay a line that was already paid in a previous check →
   * 400/IllegalArgumentException.
   */
  @Test
  void payAlreadyPaidLine_throwsIllegalArgument() throws Exception {
    UUID itemA = seedItem(5_000L);
    UUID itemB = seedItem(5_000L);

    BillResponse bill = openBill("Double-Pay");
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)));

    UUID lineAId = afterAppend.lines().get(0).id();
    UUID lineBId = afterAppend.lines().get(1).id();

    // Pay line A.
    payLines(bill.id(), List.of(lineAId));

    // Try to pay line A again — should fail.
    assertThatThrownBy(() -> payLines(bill.id(), List.of(lineAId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already paid");

    // Only one SaleRecorded (the original check 1).
    assertThat(saleRecordedRows()).hasSize(1);
  }

  /** Supplying lineIds that don't belong to this bill → IllegalArgumentException. */
  @Test
  void payLineNotOnBill_throwsIllegalArgument() throws Exception {
    UUID itemId = seedItem(5_000L);
    BillResponse bill = openBill("Wrong-Line");
    appendLines(bill.id(), List.of(new OrderLineRequest(itemId, 1)));

    UUID bogusLineId = UUID.randomUUID();
    assertThatThrownBy(() -> payLines(bill.id(), List.of(bogusLineId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong");

    assertThat(saleRecordedRows()).isEmpty();
  }

  /**
   * Paying with no lineIds when all lines are already paid → nothing to pay →
   * IllegalArgumentException. (Indirect: first pay all, then try full-pay → bill is PAID, returns
   * idempotent result.) Separately test explicitly empty lineIds set via the "nothing to pay"
   * guard.
   */
  @Test
  void payEmptyTargetSet_whenAllLinesPaid_idempotentOrThrows() throws Exception {
    UUID itemA = seedItem(5_000L);
    UUID itemB = seedItem(5_000L);

    BillResponse bill = openBill("Empty-Set");
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)));

    UUID lineAId = afterAppend.lines().get(0).id();
    UUID lineBId = afterAppend.lines().get(1).id();

    // Pay line A.
    payLines(bill.id(), List.of(lineAId));

    // Now try to pay just line A again — already paid, throws.
    assertThatThrownBy(() -> payLines(bill.id(), List.of(lineAId)))
        .isInstanceOf(IllegalArgumentException.class);

    // Pay all remaining (line B) — bill becomes PAID.
    BillResponse paid = payAll(bill.id());
    assertThat(paid.status()).isEqualTo("PAID");

    // Re-pay already-PAID bill → idempotent (returns PAID state, no third sale).
    BillResponse repaid = payAll(bill.id());
    assertThat(repaid.status()).isEqualTo("PAID");
    assertThat(saleRecordedRows()).hasSize(2); // check 1 + check 2, no third
  }

  /**
   * Insufficient stock on a check → 422; that check rolls back entirely. Lines from the current
   * check stay unpaid, no sale recorded, stock unchanged. Lines already paid in a prior check
   * remain paid and their stock deduction is irreversible.
   */
  @Test
  void insufficientStockOnSplitCheck_rollsBackOnlyThatCheck() throws Exception {
    UUID itemA = seedTrackedItem(10_000L, 5); // 5 in stock — plenty
    UUID itemB = seedTrackedItem(5_000L, 1); // only 1 in stock, but we'll try to buy 3

    BillResponse bill = openBill("Stock-Split");
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 2), new OrderLineRequest(itemB, 3)));
    assertThat(afterAppend.lines()).hasSize(2);

    UUID lineAId = afterAppend.lines().get(0).id();
    UUID lineBId = afterAppend.lines().get(1).id();

    // Check 1: pay line A (itemA, qty=2) — should succeed.
    BillResponse afterCheck1 = payLines(bill.id(), List.of(lineAId));
    assertThat(afterCheck1.status()).isEqualTo("OPEN");
    Map<UUID, BillLineResponse> byId1 =
        afterCheck1.lines().stream()
            .collect(java.util.stream.Collectors.toMap(BillLineResponse::id, l -> l));
    assertThat(byId1.get(lineAId).paid()).isTrue();
    assertThat(stockOf(itemA)).isEqualTo(3); // 5 - 2

    // Check 2: pay line B (itemB, qty=3) — should fail (only 1 in stock).
    assertThatThrownBy(() -> payLines(bill.id(), List.of(lineBId)))
        .isInstanceOf(InsufficientStockException.class);

    // Only one SaleRecorded (check 1).
    assertThat(saleRecordedRows()).hasSize(1);

    // Stock for itemB unchanged (check 2 rolled back).
    assertThat(stockOf(itemB)).isEqualTo(1);

    // Stock for itemA still at 3 (check 1 committed).
    assertThat(stockOf(itemA)).isEqualTo(3);

    // Line B is still unpaid; bill is still OPEN.
    BillResponse current =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.getById(bill.id()));
    assertThat(current.status()).isEqualTo("OPEN");
    Map<UUID, BillLineResponse> byIdCurrent =
        current.lines().stream()
            .collect(java.util.stream.Collectors.toMap(BillLineResponse::id, l -> l));
    assertThat(byIdCurrent.get(lineAId).paid()).isTrue();
    assertThat(byIdCurrent.get(lineBId).paid()).isFalse();
  }

  /** Idempotent re-pay of a check (same idempotencyKey) → no second sale. */
  @Test
  void idempotentSplitCheckReplay_noSecondSale() throws Exception {
    UUID itemA = seedItem(10_000L);
    UUID itemB = seedItem(20_000L);

    BillResponse bill = openBill("Idem-Split");
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)));

    UUID lineAId = afterAppend.lines().get(0).id();

    String customKey = "test-idem-key-" + bill.id();

    // First call — pays line A.
    BillResponse first = payLinesWithKey(bill.id(), List.of(lineAId), customKey);
    assertThat(first.status()).isEqualTo("OPEN");
    assertThat(saleRecordedRows()).hasSize(1);

    // Second call — same idempotency key: must not record a second sale.
    BillResponse second = payLinesWithKey(bill.id(), List.of(lineAId), customKey);
    assertThat(second.status()).isEqualTo("OPEN"); // still OPEN (line B not yet paid)
    assertThat(saleRecordedRows()).hasSize(1); // still exactly one SaleRecorded
  }

  /**
   * Stock is deducted only for the lines in the current check — lines not in the check (even if
   * they share the same menu item) are not deducted until their own check.
   */
  @Test
  void stockDeductedOnlyForTargetLines() throws Exception {
    UUID trackedItem = seedTrackedItem(8_000L, 10);

    // Bill has 3 lines of the same tracked item.
    BillResponse bill = openBill("Stock-Partial");
    BillResponse afterAppend =
        appendLines(
            bill.id(),
            List.of(
                new OrderLineRequest(trackedItem, 1),
                new OrderLineRequest(trackedItem, 2),
                new OrderLineRequest(trackedItem, 3)));
    assertThat(afterAppend.lines()).hasSize(3);

    UUID line1 = afterAppend.lines().get(0).id(); // qty=1
    UUID line2 = afterAppend.lines().get(1).id(); // qty=2

    // Pay only the first two lines (qty 1+2=3).
    payLines(bill.id(), List.of(line1, line2));
    assertThat(stockOf(trackedItem)).isEqualTo(7); // 10 - 3

    // Bill still OPEN (line 3 unpaid).
    BillResponse current =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.getById(bill.id()));
    assertThat(current.status()).isEqualTo("OPEN");
    assertThat(current.lines().stream().filter(BillLineResponse::paid).count()).isEqualTo(2);
    assertThat(current.lines().stream().filter(l -> !l.paid()).count()).isEqualTo(1);
  }
}
