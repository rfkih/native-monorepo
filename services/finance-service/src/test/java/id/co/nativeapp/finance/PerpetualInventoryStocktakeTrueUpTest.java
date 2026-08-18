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
import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
import id.co.nativeapp.finance.stocktake.service.StocktakeService;
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
 * ADR 0067 Phase D, D3 — the stocktake true-up proving test (§4). {@code finance-service}'s {@code
 * StocktakeWriter} (Dr 5800/Cr 1100 on a LOSS) is verified UNCHANGED by D3, per the ADR: it simply
 * trues a NOW-REAL {@code 1100} once purchases capitalize and sales expense it. This test extends
 * {@link SaleCogsRecordedParityTest}'s buy→receive→sell chain with a FOURTH leg — a physical
 * ingredient stocktake — and proves the whole cycle for a TEST-ACTIVATED tenant:
 *
 * <ol>
 *   <li><strong>buy</strong> — an inventory-flagged AP bill line (Dr 2050 GRNI / Cr 2000 AP)
 *   <li><strong>receive</strong> — {@code StockReceived} capitalizes the EXACT landed value to
 *       {@code 1100} (Dr 1100 / Cr 2050)
 *   <li><strong>sell</strong> — {@code SaleCogsRecorded} expenses part of that inventory (Dr 5100 /
 *       Cr 1100)
 *   <li><strong>physical stocktake</strong> — {@code StocktakeCompleted} trues the residual {@code
 *       1100} to the COUNTED value (Dr 5800/Cr 1100 on a loss)
 * </ol>
 *
 * <p><strong>The value-basis proof (§4, the D3 VERIFY step).</strong> {@code shrinkage_minor} here
 * is computed EXACTLY as {@code restaurant-service}'s {@code IngredientStocktakeWriter} computes it
 * — {@code varianceQty × ingredient.unitCostMinor}, where {@code unitCostMinor} is the ingredient's
 * MOVING-AVERAGE unit cost (ADR 0056, V36: {@code round(stockValueMinor / stockQty)}), never a
 * menu-item static cost (a DIFFERENT concern the ADR explicitly keeps out of the {@code 1100}
 * true-up — the legacy menu-item stocktake, {@code stocktake.service.StocktakeWriter} in
 * restaurant-service, is a separate flow this test does not exercise). Verified: with a landed
 * value of 500_000 for 10 units (50_000/unit, no rounding drift), a COGS fold of 200_000 (4 units
 * at the same 50_000/unit), the book quantity is 6 units / 300_000. A physical count of 4 units is
 * a variance of −2 units × 50_000/unit = −100_000 → {@code shrinkage_minor = 100_000} (a loss).
 * After the true-up, {@code 1100} = 300_000 − 100_000 = 200_000 = the COUNTED 4 units × 50_000/unit
 * — {@code 1100} trues to the physically-counted value, valued at the SAME moving-average basis the
 * receipt/COGS legs built it from. {@code finance-service}'s {@code StocktakeWriter} itself needed
 * NO code change for this — it already posts the producer's signed {@code shrinkage_minor} verbatim
 * (unchanged since ADR 0038); this test is the proving artifact D3 calls for.
 */
@SpringBootTest
class PerpetualInventoryStocktakeTrueUpTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-fffffffffff2";
  private static final String ACTOR = "adr0067-trueup@isolation.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22334455-2233-4455-2233-445522334455");

  private static final long LANDED_VALUE_MINOR = 500_000L; // 10 units @ 50_000/unit
  private static final long COGS_MINOR = 200_000L; // 4 units @ 50_000/unit
  private static final long UNIT_COST_MINOR = 50_000L;
  private static final int RECEIVED_QTY = 10;
  private static final int DEPLETED_QTY = 4;
  private static final int SYSTEM_QTY = RECEIVED_QTY - DEPLETED_QTY; // book qty = 6
  private static final int COUNTED_QTY = 4;
  private static final int VARIANCE_QTY = COUNTED_QTY - SYSTEM_QTY; // -2 (a shortfall)
  private static final long SHRINKAGE_MINOR =
      -((long) VARIANCE_QTY * UNIT_COST_MINOR); // -(-2 * 50_000) = 100_000 (a LOSS)

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private StockReceivedService stockReceivedService;
  @Autowired private SaleCogsRecordedService saleCogsRecordedService;
  @Autowired private StocktakeService stocktakeService;

  @Test
  void buyReceiveSellThenPhysicalStocktakeTruesInventoryToTheCountedMovingAverageValue()
      throws Exception {
    activatePerpetualInventory(TENANT);
    Instant now = Instant.now();

    // 1) BUY — an inventory-flagged AP bill line (Dr 2050 GRNI / Cr 2000 AP, no VAT to keep the
    // numbers exact).
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          VendorResponse vendor = vendorWriter.create("True-up Supplier", "ap@trueup.test", null);
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
    UUID ingredientId = UUID.randomUUID();
    StockReceivedEvent received =
        new StockReceivedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TENANT,
            BUSINESS_ID,
            ingredientId,
            RECEIVED_QTY,
            LANDED_VALUE_MINOR,
            "IDR",
            now);
    assertThat(stockReceivedService.handle(received)).isTrue();
    assertThat(accountNetAsAdmin("1100")).isEqualTo(LANDED_VALUE_MINOR);

    // 3) SELL — SaleCogsRecorded expenses part of that inventory (Dr 5100 / Cr 1100). Book qty
    // after depletion = 10 - 4 = 6 units, value = 500_000 - 200_000 = 300_000 (= 6 * 50_000/unit,
    // no rounding drift).
    SaleCogsRecordedEvent sold =
        new SaleCogsRecordedEvent(
            UUID.randomUUID(), UUID.randomUUID(), TENANT, BUSINESS_ID, now, COGS_MINOR, "IDR");
    assertThat(saleCogsRecordedService.handle(sold)).isTrue();
    long bookValueBeforeStocktake = LANDED_VALUE_MINOR - COGS_MINOR;
    assertThat(accountNetAsAdmin("1100")).isEqualTo(bookValueBeforeStocktake);
    assertThat(bookValueBeforeStocktake).isEqualTo((long) SYSTEM_QTY * UNIT_COST_MINOR);

    // 4) PHYSICAL STOCKTAKE — a shortfall of 2 units (system 6, counted 4), valued at the SAME
    // moving-average unit cost the receipt/COGS legs used (§4's value-basis requirement) —
    // shrinkage_minor = 100_000 (a LOSS): Dr 5800 / Cr 1100.
    assertThat(SHRINKAGE_MINOR).isEqualTo(100_000L);
    StocktakeCompletedEvent counted =
        new StocktakeCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), TENANT, BUSINESS_ID, now, SHRINKAGE_MINOR, "IDR");
    assertThat(stocktakeService.handle(counted)).isTrue();

    // 5) 1100 TRUES to the COUNTED value: 300_000 - 100_000 = 200_000 = COUNTED_QTY(4) *
    // UNIT_COST_MINOR(50_000). This is the D3 proof — no code change was needed for it to hold.
    long inventoryAfterTrueUp = accountNetAsAdmin("1100");
    long countedValue = (long) COUNTED_QTY * UNIT_COST_MINOR;
    assertThat(inventoryAfterTrueUp).isEqualTo(countedValue);
    assertThat(inventoryAfterTrueUp).isEqualTo(200_000L);
    assertThat(inventoryAfterTrueUp).isNotNegative();

    // The shrinkage entry itself: exactly Dr 5800 / Cr 1100 for 100_000.
    assertThat(accountDebitForEventAsAdmin(counted.eventId(), "5800")).isEqualTo(100_000L);
    assertThat(accountCreditForEventAsAdmin(counted.eventId(), "1100")).isEqualTo(100_000L);
  }

  private void activatePerpetualInventory(String companyId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO inventory_method_config"
                    + " (id, method, perpetual_active, cutover_period, activated_at, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, 'PERPETUAL', true, NULL, now(), now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, companyId);
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

  private long accountDebitForEventAsAdmin(UUID sourceEventId, String accountCode)
      throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(jl.debit_minor), 0) FROM journal_line jl"
                    + " JOIN journal_entry je ON je.id = jl.entry_id"
                    + " WHERE je.source_event_id = ? AND jl.account_code = ?")) {
      ps.setObject(1, sourceEventId);
      ps.setString(2, accountCode);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long accountCreditForEventAsAdmin(UUID sourceEventId, String accountCode)
      throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(jl.credit_minor), 0) FROM journal_line jl"
                    + " JOIN journal_entry je ON je.id = jl.entry_id"
                    + " WHERE je.source_event_id = ? AND jl.account_code = ?")) {
      ps.setObject(1, sourceEventId);
      ps.setString(2, accountCode);
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
