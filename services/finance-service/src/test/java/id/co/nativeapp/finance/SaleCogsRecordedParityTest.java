package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.inventory.messaging.SaleCogsRecordedEvent;
import id.co.nativeapp.finance.inventory.messaging.StockReceivedEvent;
import id.co.nativeapp.finance.inventory.service.SaleCogsRecordedService;
import id.co.nativeapp.finance.inventory.service.StockReceivedService;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import id.co.nativeapp.tenant.TenantContext;
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
 * ADR 0067 Phase C acceptance — the end-to-end parity proof: for a TEST-ACTIVATED tenant, {@code
 * buy} (an inventory-flagged AP bill line) → {@code receive} ({@code StockReceived}) → {@code sell}
 * ({@code SaleCogsRecorded}) leaves {@code 1100 Inventory} = landed value − COGS (non-negative),
 * inventory expensed EXACTLY once, and the COGS shows on the GL-derived income statement (ADR 0065
 * — no separate {@code consolidated_pnl} write needed for it to appear there). Reuses the Phase B
 * activation helper ({@link BillInventoryRoutingTest}/{@link StockReceivedWriterTest} pattern —
 * NULL {@code cutover_period}, deterministic regardless of wall-clock).
 */
@SpringBootTest
class SaleCogsRecordedParityTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-fffffffffff1";
  private static final String ACTOR = "adr0067-parity@isolation.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("11223344-1122-3344-1122-334411223344");

  private static final long LANDED_VALUE_MINOR = 500_000L;
  private static final long COGS_MINOR = 200_000L;

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private StockReceivedService stockReceivedService;
  @Autowired private SaleCogsRecordedService saleCogsRecordedService;
  @Autowired private IncomeStatementReader incomeStatementReader;

  @Test
  void buyReceiveSellLeavesInventoryNonNegativeExpensedOnceAndOnTheIncomeStatement()
      throws Exception {
    activatePerpetualInventory(TENANT);
    Instant now = Instant.now();
    String period = LedgerPosting.periodOf(now);

    // 1) BUY — an inventory-flagged AP bill line (Dr 2050 GRNI / Cr 2000 AP, no VAT to keep the
    // numbers exact).
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          VendorResponse vendor = vendorWriter.create("Parity Supplier", "ap@parity.test", null);
          UUID billId =
              billWriter.createDraft(
                  vendor.id(),
                  "IDR",
                  false,
                  List.of(new BillLineInput("Raw ingredients", 1, LANDED_VALUE_MINOR, true)));
          billWriter.post(billId, null);
          return billId;
        });

    // 2) RECEIVE — StockReceived capitalizes the EXACT landed value to 1100 (Dr 1100 / Cr 2050).
    UUID receiptId = UUID.randomUUID();
    UUID ingredientId = UUID.randomUUID();
    StockReceivedEvent received =
        new StockReceivedEvent(
            UUID.randomUUID(),
            receiptId,
            TENANT,
            BUSINESS_ID,
            ingredientId,
            10L,
            LANDED_VALUE_MINOR,
            "IDR",
            now);
    assertThat(stockReceivedService.handle(received)).isTrue();

    // 3) SELL — SaleCogsRecorded expenses part of that inventory (Dr 5100 / Cr 1100).
    UUID saleId = UUID.randomUUID();
    SaleCogsRecordedEvent sold =
        new SaleCogsRecordedEvent(
            UUID.randomUUID(), saleId, TENANT, BUSINESS_ID, now, COGS_MINOR, "IDR");
    assertThat(saleCogsRecordedService.handle(sold)).isTrue();

    // 4) 1100 = landed − COGS, non-negative — inventory is a correct asset, never driven negative.
    long inventoryBalance = accountNetAsAdmin("1100");
    assertThat(inventoryBalance).isEqualTo(LANDED_VALUE_MINOR - COGS_MINOR);
    assertThat(inventoryBalance).isNotNegative();

    // 5) Inventory expensed EXACTLY once — a single 5100 debit line for the COGS amount.
    assertThat(journalLineCountAsAdmin("5100")).isEqualTo(1L);
    assertThat(accountDebitAsAdmin("5100")).isEqualTo(COGS_MINOR);

    // 6) The COGS shows on the GL-derived income statement (ADR 0065) — no consolidated_pnl write
    // required for it to surface here.
    IncomeStatementResponse income =
        TenantContext.callAs(TENANT, ACTOR, () -> incomeStatementReader.read(period)).orElseThrow();
    assertThat(income.totalExpenseMinor())
        .as("COGS is the only P&L-relevant posting this period (GRNI/AP/Inventory are B/S-only)")
        .isEqualTo(COGS_MINOR);
    assertThat(income.totalRevenueMinor()).isZero();
    assertThat(income.netMinor()).isEqualTo(-COGS_MINOR);
  }

  private void activatePerpetualInventory(String companyId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO inventory_method_config"
                    + " (id, method, perpetual_active, cutover_period, activated_at, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id, currency,"
                    + " idempotency_key, opening_inventory_value_minor)"
                    + " VALUES (?, 'PERPETUAL', true, NULL, now(), now(), 'test', now(),"
                    + " 'test', 0, ?, 'IDR', ?, 0)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, companyId);
      ps.setString(3, "test-activate-" + companyId);
      ps.executeUpdate();
    }
  }

  /**
   * {@code Σdebit − Σcredit} for {@code accountCode}, over every journal line this tenant wrote.
   */
  private long accountNetAsAdmin(String accountCode) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0)"
                    + " FROM journal_line WHERE account_code = ? AND company_id = ?")) {
      ps.setString(1, accountCode);
      ps.setString(2, TENANT);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long accountDebitAsAdmin(String accountCode) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(debit_minor), 0)"
                    + " FROM journal_line WHERE account_code = ? AND company_id = ?")) {
      ps.setString(1, accountCode);
      ps.setString(2, TENANT);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long journalLineCountAsAdmin(String accountCode) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM journal_line"
                    + " WHERE account_code = ? AND company_id = ? AND debit_minor > 0")) {
      ps.setString(1, accountCode);
      ps.setString(2, TENANT);
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
