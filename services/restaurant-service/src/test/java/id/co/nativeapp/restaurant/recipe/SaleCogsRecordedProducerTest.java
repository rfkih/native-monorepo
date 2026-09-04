package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.messaging.SaleCogsRecordedSchema;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0067 Phase C (restaurant producer side, §1 "SaleCogsRecorded"): {@code
 * IngredientDepletionWriter}'s COGS fold, snapshotted onto {@code sale.cogs_minor}/{@code
 * cogs_currency} and emitted as ONE {@code SaleCogsRecorded} outbox row in the SAME transaction as
 * the sale + depletion — exercised through the REAL sale-recording paths (cash checkout via {@code
 * OrderWriter}, split-check {@code payBill} via {@code BillWriter}) exactly like {@link
 * IngredientDepletionIntegrationTest}, so a producer-wiring regression in either path is caught
 * here.
 */
@SpringBootTest
class SaleCogsRecordedProducerTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-cogs@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private IngredientService ingredientService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;
  @Autowired private BillService billService;

  @Test
  void aSaleWithCostedRecipeDepletionPersistsCogsAndEmitsOneSaleCogsRecordedEvent()
      throws Exception {
    UUID ingredientId = createIngredient("Beef", 1_000, 50L, "IDR");
    UUID itemId = createItem("Burger", 25_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 80)));

    CheckoutResult result = checkout("cogs-checkout-001", List.of(new OrderLineRequest(itemId, 3)));
    UUID saleId = result.order().saleId();

    // Σ (depleted qty × unit cost) = (80 * 3) * 50 = 12_000.
    assertThat(cogsMinorOfSale(saleId)).isEqualTo(12_000L);
    assertThat(cogsCurrencyOfSale(saleId)).isEqualTo("IDR");

    List<GenericRecord> events = saleCogsRecordedEventsFor(saleId);
    assertThat(events).as("exactly one SaleCogsRecorded event").hasSize(1);
    GenericRecord event = events.get(0);
    assertThat(event.get("sale_id").toString()).isEqualTo(saleId.toString());
    assertThat(event.get("business_id").toString()).isEqualTo(BUSINESS_ID.toString());
    assertThat(event.get("cogs_minor")).isEqualTo(12_000L);
    assertThat(event.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void theCogsFoldMatchesTheExactMovingAverageValueTheDepletionPathBooksOut() throws Exception {
    // 200 g @ 33/g => value 6_600. Depleting 40 g books out value 40*33=1_320 (an integer-clean
    // average, so the value-bucket delta and the COGS fold must agree EXACTLY).
    UUID ingredientId = createIngredient("Chicken", 200, 33L, "IDR");
    long valueBefore = stockValueOfIngredient(ingredientId);
    UUID itemId = createItem("Ayam", 18_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 40)));

    CheckoutResult result =
        checkout("cogs-fold-match-001", List.of(new OrderLineRequest(itemId, 1)));
    UUID saleId = result.order().saleId();

    long valueAfter = stockValueOfIngredient(ingredientId);
    long bookedOutValue = valueBefore - valueAfter;

    assertThat(bookedOutValue).isEqualTo(1_320L);
    assertThat(cogsMinorOfSale(saleId))
        .as("the SaleCogsRecorded fold must equal the EXACT value the depletion path booked out")
        .isEqualTo(bookedOutValue);
  }

  @Test
  void aSaleWithNoRecipeDepletionLeavesCogsNullAndEmitsNoSaleCogsRecordedEvent() throws Exception {
    UUID noRecipeItem = createItem("Plain Water", 5_000L);

    CheckoutResult result =
        checkout("cogs-norecipe-001", List.of(new OrderLineRequest(noRecipeItem, 5)));
    UUID saleId = result.order().saleId();

    assertThat(cogsMinorIsNullForSale(saleId)).isTrue();
    assertThat(cogsCurrencyOfSale(saleId)).isNull();
    assertThat(saleCogsRecordedEventsFor(saleId)).isEmpty();
  }

  @Test
  void anUncostedIngredientRecipeLeavesCogsNullAndEmitsNoSaleCogsRecordedEvent() throws Exception {
    // A recipe exists and depletes stock, but the ingredient carries NO cost — nothing to fold.
    UUID ingredientId = createIngredient("Garnish", 500, null, null);
    UUID itemId = createItem("Decorated Plate", 12_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 5)));

    CheckoutResult result = checkout("cogs-uncosted-001", List.of(new OrderLineRequest(itemId, 2)));
    UUID saleId = result.order().saleId();

    // Depletion still ran (proves this is a costing gap, not a depletion no-op).
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(500 - 10);
    assertThat(cogsMinorIsNullForSale(saleId)).isTrue();
    assertThat(saleCogsRecordedEventsFor(saleId)).isEmpty();
  }

  @Test
  void splitCheckPaymentAlsoFoldsAndEmitsCogsViaTheBillWriterPath() throws Exception {
    UUID ingredientId = createIngredient("Ing Cogs Bill", 1_000, 25L, "IDR");
    UUID itemId = createItem("Item Cogs Bill", 10_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 30)));

    BillResponse bill = openBill("Cogs-Split");
    BillResponse afterAppend = appendLines(bill.id(), List.of(new OrderLineRequest(itemId, 2)));
    UUID lineId = afterAppend.lines().get(0).id();

    payLines(bill.id(), List.of(lineId));

    // Σ = (30 * 2) * 25 = 1_500. Find the sale via the outbox event's own payload (no separate
    // sale-id accessor on BillResponse) — exactly one SaleCogsRecorded should exist company-wide.
    List<GenericRecord> events = allSaleCogsRecordedEvents();
    assertThat(events).hasSize(1);
    GenericRecord event = events.get(0);
    assertThat(event.get("cogs_minor")).isEqualTo(1_500L);
    assertThat(event.get("currency").toString()).isEqualTo("IDR");
    assertThat(cogsMinorOfSale(UUID.fromString(event.get("sale_id").toString()))).isEqualTo(1_500L);
  }

  @Test
  void aSaleFailureAfterDepletionRollsBackTheCogsSnapshotAndOutboxEventToo() throws Exception {
    UUID ingredientId = createIngredient("Atomic Cogs", 1_000, 10L, "IDR");
    UUID itemId = createItem("Atomic Cogs Item", 40_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 50)));

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "cogs-atomic-fail-001",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 1_000L)); // far less than the total due

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("less than the amount due");

    // Whole REQUIRES_NEW checkout tx rolled back — no sale row, no SaleCogsRecorded outbox row,
    // and the ingredient's moving-average value is untouched (rolls back together, rule 3).
    assertThat(rowCountAsAdmin("sale")).isZero();
    assertThat(allSaleCogsRecordedEvents()).isEmpty();
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createItem(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS_ID, name, "MAIN", priceMinor, "IDR"))
                .id());
  }

  private UUID createIngredient(String name, int initialStock, Long unitCostMinor, String currency)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingredientService
                .create(
                    new CreateIngredientRequest(
                        BUSINESS_ID, name, "g", null, unitCostMinor, currency, initialStock))
                .id());
  }

  private void putRecipe(UUID itemId, List<RecipeLineInput> lines) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          recipeService.putRecipe(itemId, new PutRecipeRequest(lines));
          return null;
        });
  }

  private CheckoutResult checkout(String idempotencyKey, List<OrderLineRequest> lines)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> orderService.checkout(new CheckoutRequest(BUSINESS_ID, idempotencyKey, lines)));
  }

  private BillResponse openBill(String guestLabel) throws Exception {
    return TenantContext.callAs(
        TENANT, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, guestLabel)));
  }

  private BillResponse appendLines(UUID billId, List<OrderLineRequest> lines) throws Exception {
    return TenantContext.callAs(
        TENANT, ACTOR, () -> billService.appendLines(billId, new AppendLinesRequest(lines)));
  }

  private BillResponse payLines(UUID billId, List<UUID> lineIds) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> billService.payBill(billId, new PayBillRequest(null, null, lineIds, null, null)));
  }

  private int stockOfIngredient(UUID ingredientId) throws Exception {
    return (int) longColumnOfIngredient(ingredientId, "stock_qty");
  }

  private long stockValueOfIngredient(UUID ingredientId) throws Exception {
    return longColumnOfIngredient(ingredientId, "stock_value_minor");
  }

  private long longColumnOfIngredient(UUID ingredientId, String column) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT " + column + " FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Long cogsMinorOfSale(UUID saleId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps = admin.prepareStatement("SELECT cogs_minor FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long value = rs.getLong(1);
        return rs.wasNull() ? null : value;
      }
    }
  }

  private boolean cogsMinorIsNullForSale(UUID saleId) throws Exception {
    return cogsMinorOfSale(saleId) == null;
  }

  private String cogsCurrencyOfSale(UUID saleId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT cogs_currency FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        String value = rs.getString(1);
        return value == null ? null : value.strip();
      }
    }
  }

  private List<GenericRecord> saleCogsRecordedEventsFor(UUID saleId) throws Exception {
    return allSaleCogsRecordedEvents().stream()
        .filter(r -> r.get("sale_id").toString().equals(saleId.toString()))
        .toList();
  }

  private List<GenericRecord> allSaleCogsRecordedEvents() throws Exception {
    List<GenericRecord> events = new ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'SaleCogsRecorded'");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        byte[] payload = rs.getBytes("payload");
        events.add(AvroSerde.deserialize(payload, SaleCogsRecordedSchema.schema()));
      }
    }
    return events;
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
