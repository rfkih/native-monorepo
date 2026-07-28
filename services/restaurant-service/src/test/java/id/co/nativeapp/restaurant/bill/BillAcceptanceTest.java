package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.domain.BillNotOpenException;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.BillSummaryResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.service.TableService;
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
 * Acceptance tests for the bill (guest tab) feature.
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Open a new empty bill → 201, OPEN status, no lines, no sale.
 *   <li>Append rounds → lines accumulate, running total updates.
 *   <li>List OPEN bills by business; optionally by table — multiple guests coexist.
 *   <li>Pay → exactly one SaleRecorded emitted, stock deducted, status PAID.
 *   <li>Re-pay (idempotent) → no second SaleRecorded, bill stays PAID.
 *   <li>Pay with insufficient stock → 422 + full rollback (bill stays OPEN, stock unchanged).
 *   <li>Cancel OPEN bill → no sale, no stock change, status CANCELLED.
 *   <li>Remove a line from an OPEN bill.
 *   <li>Cross-tenant isolation: tenant B cannot see/pay tenant A's bills.
 * </ul>
 */
@SpringBootTest
class BillAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aa220001-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bb220001-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("cc220001-cccc-cccc-cccc-cccccccccccc");

  @Autowired private BillService billService;
  @Autowired private MenuService menuService;
  @Autowired private TableService tableService;
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
                    new CreateMenuItemRequest(BUSINESS, "Nasi Goreng", "MAIN", price, "IDR"))
                .id());
  }

  private UUID seedTrackedItem(long price, int stock) throws Exception {
    UUID id = seedItem(price);
    executeAsAdmin("UPDATE menu_item SET stock_quantity = " + stock + " WHERE id = '" + id + "'");
    return id;
  }

  private UUID seedTable() throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            tableService
                .create(new CreateTableRequest(BUSINESS, "T-Bill-1", 4, "Indoor"))
                .tableId());
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  @Test
  void openBillCreatesAnEmptyOpenBillAndListIncludesIt() throws Exception {
    UUID tableId = seedTable();
    UUID itemId = seedItem(15_000L);

    // Open two bills for the same table (two guests coexist).
    BillResponse guestA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, tableId, "Guest A")));
    BillResponse guestB =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, tableId, "Guest B")));

    assertThat(guestA.status()).isEqualTo("OPEN");
    assertThat(guestA.tableId()).isEqualTo(tableId);
    assertThat(guestA.guestLabel()).isEqualTo("Guest A");
    assertThat(guestA.lines()).isEmpty();
    assertThat(guestA.saleId()).isNull();

    // List OPEN bills for the business — both guests appear.
    List<BillSummaryResponse> list =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.list(BUSINESS, "OPEN", null));
    assertThat(list).hasSize(2);
    assertThat(list.stream().map(BillSummaryResponse::guestLabel))
        .containsExactlyInAnyOrder("Guest A", "Guest B");

    // Filter by table — same result.
    List<BillSummaryResponse> byTable =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.list(BUSINESS, "OPEN", tableId));
    assertThat(byTable).hasSize(2);

    // Append a line to Guest A.
    BillResponse afterAppend =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                billService.appendLines(
                    guestA.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 2)))));

    assertThat(afterAppend.lines()).hasSize(1);
    assertThat(afterAppend.lines().get(0).nameSnapshot()).isEqualTo("Nasi Goreng");
    assertThat(afterAppend.lines().get(0).lineTotalMinor()).isEqualTo(30_000L);
    assertThat(afterAppend.breakdown()).isNotNull();
    assertThat(afterAppend.currency()).isEqualTo("IDR");

    // Running total on list now shows the correct subtotal for Guest A.
    List<BillSummaryResponse> updatedList =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.list(BUSINESS, "OPEN", null));
    BillSummaryResponse guestASummary =
        updatedList.stream()
            .filter(s -> "Guest A".equals(s.guestLabel()))
            .findFirst()
            .orElseThrow();
    assertThat(guestASummary.runningTotalMinor()).isEqualTo(30_000L);
    assertThat(guestASummary.lineCount()).isEqualTo(1);
  }

  @Test
  void payBillRecordsExactlyOneSaleRecordedAndDeductsStockAndTransitionsToPaid() throws Exception {
    UUID itemId = seedTrackedItem(10_000L, 10);

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Solo")));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 3)))));

    // Pay the bill.
    BillResponse paid =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.payBill(bill.id(), new PayBillRequest()));

    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(paid.saleId()).isNotNull();
    assertThat(paid.breakdown()).isNotNull();
    // 3 × IDR 10,000 = subtotal 30,000. No tax/SC rules seeded for this test tenant, so
    // grandTotal = subtotal = 30,000 (TaxChargeService falls through to zero-rate when no rules).
    assertThat(paid.breakdown().subtotalMinor()).isEqualTo(30_000L);
    assertThat(paid.breakdown().grandTotalMinor()).isEqualTo(30_000L);

    // Exactly one SaleRecorded in the outbox.
    List<Map<String, Object>> outboxRows = saleRecordedRows();
    assertThat(outboxRows).hasSize(1);
    Map<String, Object> outboxRow = outboxRows.getFirst();
    assertThat(outboxRow.get("company_id")).hasToString(TENANT_A);

    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) outboxRow.get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(30_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");

    // Stock deducted.
    int stockAfter = rowAsAdmin("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'");
    assertThat(stockAfter).isEqualTo(7); // 10 - 3

    // Bill row is PAID.
    assertThat(rowCountAsAdmin("bill WHERE status = 'PAID'")).isEqualTo(1L);
  }

  @Test
  void rePayAAlreadyPaidBillIsIdempotentAndEmitsNoSecondSaleRecorded() throws Exception {
    UUID itemId = seedItem(10_000L);

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Idem")));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 1)))));

    // First pay.
    BillResponse firstPay =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.payBill(bill.id(), new PayBillRequest()));
    assertThat(firstPay.status()).isEqualTo("PAID");
    assertThat(saleRecordedRows()).hasSize(1);

    // Second pay — idempotent, no second SaleRecorded.
    BillResponse secondPay =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.payBill(bill.id(), new PayBillRequest()));
    assertThat(secondPay.status()).isEqualTo("PAID");
    assertThat(secondPay.saleId()).isEqualTo(firstPay.saleId());
    assertThat(saleRecordedRows()).hasSize(1); // still exactly one
  }

  @Test
  void payWithInsufficientStockRollsBackEverythingAndBillStaysOpen() throws Exception {
    UUID itemId = seedTrackedItem(5_000L, 2); // only 2 in stock

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Short")));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(),
                new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 5))))); // request 5

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR, () -> billService.payBill(bill.id(), new PayBillRequest())))
        .isInstanceOf(id.co.nativeapp.restaurant.menu.domain.InsufficientStockException.class);

    // No SaleRecorded emitted.
    assertThat(saleRecordedRows()).isEmpty();

    // Stock unchanged.
    int stockAfter = rowAsAdmin("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'");
    assertThat(stockAfter).isEqualTo(2);

    // Bill is still OPEN.
    BillResponse current =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.getById(bill.id()));
    assertThat(current.status()).isEqualTo("OPEN");
  }

  @Test
  void cancelOpenBillProducesNoSaleAndNoStock() throws Exception {
    UUID itemId = seedTrackedItem(8_000L, 5);

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, null, "Cancel me")));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 2)))));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          billService.cancelBill(bill.id());
          return null;
        });

    // No sale, no outbox event.
    assertThat(saleRecordedRows()).isEmpty();

    // Stock unchanged.
    int stockAfter = rowAsAdmin("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'");
    assertThat(stockAfter).isEqualTo(5);

    // Bill is CANCELLED — cannot pay it.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR, () -> billService.payBill(bill.id(), new PayBillRequest())))
        .isInstanceOf(BillNotOpenException.class);
  }

  @Test
  void removeALineFromAnOpenBill() throws Exception {
    UUID itemA = seedItem(5_000L);
    UUID itemB = seedItem(7_000L);

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Remove")));

    // Append two items.
    BillResponse afterAppend =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                billService.appendLines(
                    bill.id(),
                    new AppendLinesRequest(
                        List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)))));

    assertThat(afterAppend.lines()).hasSize(2);
    UUID lineAId = afterAppend.lines().get(0).id();

    // Remove line A.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          billService.removeLine(bill.id(), lineAId);
          return null;
        });

    // Verify only one line remains.
    BillResponse current =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.getById(bill.id()));
    assertThat(current.lines()).hasSize(1);
    assertThat(current.lines().get(0).menuItemId()).isEqualTo(itemB);
  }

  @Test
  void crossTenantIsolationTenantBCannotSeeTenantABills() throws Exception {
    UUID itemId = seedItem(10_000L);

    BillResponse billA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, null, "Tenant A")));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                billA.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 1)))));

    // Tenant B cannot see the bill.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR, () -> billService.getById(billA.id())))
        .isInstanceOf(BillNotFoundException.class);

    // Tenant B's list is empty.
    List<BillSummaryResponse> tenantBList =
        TenantContext.callAs(TENANT_B, ACTOR, () -> billService.list(BUSINESS, "OPEN", null));
    assertThat(tenantBList).isEmpty();

    // Tenant B cannot pay tenant A's bill.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR, () -> billService.payBill(billA.id(), new PayBillRequest())))
        .isInstanceOf(BillNotFoundException.class);
  }

  @Test
  void multipleRoundsAccumulateAndGetByIdShowsLiveBreakdown() throws Exception {
    UUID itemId = seedItem(10_000L);

    BillResponse bill =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Multi")));

    // Round 1.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 2)))));

    // Round 2.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            billService.appendLines(
                bill.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 1)))));

    // GET — should show 2 lines (one per append round) and a live breakdown.
    BillResponse detail =
        TenantContext.callAs(TENANT_A, ACTOR, () -> billService.getById(bill.id()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.breakdown()).isNotNull();
    // Subtotal = (2 + 1) × IDR 10,000 = 30,000; same breakdown as before.
    assertThat(detail.breakdown().subtotalMinor()).isEqualTo(30_000L);
  }

  // -----------------------------------------------------------------------
  // Infrastructure helpers
  // -----------------------------------------------------------------------

  private List<Map<String, Object>> saleRecordedRows() {
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, company_id, payload "
            + "FROM outbox WHERE event_type = 'SaleRecorded' ORDER BY occurred_at");
  }

  private long rowCountAsAdmin(String tableClause) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tableClause)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private int rowAsAdmin(String query) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(query)) {
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
}
