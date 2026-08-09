package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@code IngredientDepletionWriter} exercised through the REAL sale paths
 * (ADR 0050 phase A) — cash checkout and split-check bill payment — mirroring {@code
 * StockTrackingTest} / {@code BillSplitAcceptanceTest} for the recipe-driven ingredient depletion
 * side effect.
 *
 * <p>Covers:
 *
 * <ol>
 *   <li>(a) cash checkout with a recipe base line depletes {@code stock_qty} by qty × portion.
 *   <li>(b) a positive modifier-option delta adds usage; a negative "remove" delta reduces usage
 *       and the NET per-portion usage clamps at zero (never restocks).
 *   <li>(c) ingredient stock at 0 or short of the recipe's requirement never blocks the sale — it
 *       floors at 0.
 *   <li>(d) items without recipes leave every ingredient untouched.
 *   <li>(e) split-check {@code payBill}: paying one check depletes only that check's lines'
 *       recipes; replaying the SAME check (same derived/explicit idempotency key) never
 *       double-depletes.
 *   <li>(f) atomicity: a genuine sale failure that occurs AFTER depletion would have run (an
 *       insufficient cash tender, validated at payment-capture time — strictly after the depletion
 *       call in {@code OrderWriter.checkout}) rolls back the ingredient stock too.
 * </ol>
 */
@SpringBootTest
class IngredientDepletionIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-depletion@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;
  @Autowired private IngredientService ingredientService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;
  @Autowired private BillService billService;

  // ---------------------------------------------------------------------------
  // (a) base-line depletion via cash checkout
  // ---------------------------------------------------------------------------

  @Test
  void cashCheckoutDepletesBaseIngredientStockByQtyTimesPortion() throws Exception {
    UUID ingredientId = createIngredient("Beef", 1_000, 50L, "IDR");
    UUID itemId = createItem("Burger", 25_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 80)));

    CheckoutResult result = checkout("deplete-base-001", List.of(new OrderLineRequest(itemId, 3)));

    assertThat(result.created()).isTrue();
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000 - 80 * 3);
  }

  // ---------------------------------------------------------------------------
  // (b) modifier-option deltas
  // ---------------------------------------------------------------------------

  @Test
  void positiveModifierOptionDeltaAddsIngredientUsage() throws Exception {
    UUID ingredientId = createIngredient("Cheese", 1_000, 20L, "IDR");
    UUID itemId = createItem("Burger", 25_000L);
    UUID groupId = createGroup(itemId);
    UUID extraCheeseOption = createOption(groupId, "Extra Cheese");
    putRecipe(
        itemId,
        List.of(
            new RecipeLineInput(ingredientId, null, 10),
            new RecipeLineInput(ingredientId, extraCheeseOption, 15)));

    checkout(
        "deplete-option-001", List.of(new OrderLineRequest(itemId, 2, List.of(extraCheeseOption))));

    // net per portion = base 10 + delta 15 = 25; total = 25 * 2 = 50.
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000 - 50);
  }

  @Test
  void negativeModifierDeltaThatWouldGoBelowZeroClampsNetUsageAtZero() throws Exception {
    UUID ingredientId = createIngredient("Rice", 1_000, 5L, "IDR");
    UUID itemId = createItem("Nasi", 15_000L);
    UUID groupId = createGroup(itemId);
    UUID noRiceOption = createOption(groupId, "No Rice");
    putRecipe(
        itemId,
        List.of(
            new RecipeLineInput(ingredientId, null, 5),
            new RecipeLineInput(ingredientId, noRiceOption, -10)));

    checkout(
        "deplete-negative-clamp-001",
        List.of(new OrderLineRequest(itemId, 4, List.of(noRiceOption))));

    // net = max(0, 5 - 10) = 0 -> no depletion regardless of qty; a "remove" delta never restocks.
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000);
  }

  @Test
  void negativeModifierDeltaThatStaysPositiveStillReducesUsage() throws Exception {
    UUID ingredientId = createIngredient("Rice", 1_000, 5L, "IDR");
    UUID itemId = createItem("Nasi", 15_000L);
    UUID groupId = createGroup(itemId);
    UUID halfRiceOption = createOption(groupId, "Half Rice");
    putRecipe(
        itemId,
        List.of(
            new RecipeLineInput(ingredientId, null, 20),
            new RecipeLineInput(ingredientId, halfRiceOption, -10)));

    checkout(
        "deplete-negative-partial-001",
        List.of(new OrderLineRequest(itemId, 1, List.of(halfRiceOption))));

    // net = max(0, 20 - 10) = 10.
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000 - 10);
  }

  // ---------------------------------------------------------------------------
  // (c) short / zero ingredient stock never blocks a sale
  // ---------------------------------------------------------------------------

  @Test
  void ingredientShortOfTheRecipesRequirementStillLetsTheSaleSucceedAndFloorsAtZero()
      throws Exception {
    UUID ingredientId = createIngredient("Truffle", 5, 1_000L, "IDR");
    UUID itemId = createItem("Fancy", 100_000L);
    putRecipe(
        itemId, List.of(new RecipeLineInput(ingredientId, null, 10))); // needs 10, only 5 on hand

    CheckoutResult result = checkout("deplete-short-001", List.of(new OrderLineRequest(itemId, 1)));

    assertThat(result.created()).isTrue();
    assertThat(stockOfIngredient(ingredientId)).isZero();
  }

  @Test
  void ingredientAlreadyAtZeroStockStillLetsTheSaleSucceed() throws Exception {
    UUID ingredientId = createIngredient("Saffron", 0, 2_000L, "IDR");
    UUID itemId = createItem("Paella", 80_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 3)));

    CheckoutResult result = checkout("deplete-zero-001", List.of(new OrderLineRequest(itemId, 2)));

    assertThat(result.created()).isTrue();
    assertThat(stockOfIngredient(ingredientId)).isZero();
  }

  // ---------------------------------------------------------------------------
  // (d) items without recipes are no-ops
  // ---------------------------------------------------------------------------

  @Test
  void itemsWithoutARecipeLeaveEveryIngredientUntouched() throws Exception {
    UUID unrelatedIngredient = createIngredient("Unrelated", 500, 10L, "IDR");
    UUID noRecipeItem = createItem("Plain Water", 5_000L);

    CheckoutResult result =
        checkout("deplete-norecipe-001", List.of(new OrderLineRequest(noRecipeItem, 5)));

    assertThat(result.created()).isTrue();
    assertThat(stockOfIngredient(unrelatedIngredient)).isEqualTo(500);
  }

  // ---------------------------------------------------------------------------
  // (e) split-check payBill: per-check depletion + idempotent replay
  // ---------------------------------------------------------------------------

  @Test
  void splitCheckDepletesOnlyThePaidChecksLinesRecipes() throws Exception {
    UUID ingredientA = createIngredient("Ing A", 1_000, 10L, "IDR");
    UUID ingredientB = createIngredient("Ing B", 1_000, 10L, "IDR");
    UUID itemA = createItem("Item A", 10_000L);
    UUID itemB = createItem("Item B", 20_000L);
    putRecipe(itemA, List.of(new RecipeLineInput(ingredientA, null, 30)));
    putRecipe(itemB, List.of(new RecipeLineInput(ingredientB, null, 40)));

    BillResponse bill = openBill("Split-Depletion");
    BillResponse afterAppend =
        appendLines(
            bill.id(), List.of(new OrderLineRequest(itemA, 1), new OrderLineRequest(itemB, 1)));
    UUID lineAId = afterAppend.lines().get(0).id();
    UUID lineBId = afterAppend.lines().get(1).id();

    // Check 1: pay line A only.
    payLines(bill.id(), List.of(lineAId));
    assertThat(stockOfIngredient(ingredientA)).isEqualTo(1_000 - 30);
    assertThat(stockOfIngredient(ingredientB)).isEqualTo(1_000); // untouched — line B not paid yet

    // Check 2: pay line B.
    payLines(bill.id(), List.of(lineBId));
    assertThat(stockOfIngredient(ingredientB)).isEqualTo(1_000 - 40);
    // Ingredient A unaffected by check 2.
    assertThat(stockOfIngredient(ingredientA)).isEqualTo(1_000 - 30);
  }

  @Test
  void replayOfTheSameCheckWithTheSameIdempotencyKeyDoesNotDoubleDeplete() throws Exception {
    UUID ingredientId = createIngredient("Ing Replay", 1_000, 10L, "IDR");
    UUID itemId = createItem("Item Replay", 10_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 25)));

    BillResponse bill = openBill("Replay-Depletion");
    BillResponse afterAppend = appendLines(bill.id(), List.of(new OrderLineRequest(itemId, 1)));
    UUID lineId = afterAppend.lines().get(0).id();
    String key = "replay-check-key-" + bill.id();

    payLinesWithKey(bill.id(), List.of(lineId), key);
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000 - 25);

    // Replay: same target line set AND same explicit idempotency key — BillWriter short-circuits
    // on the caller-key check BEFORE re-validating the line set / re-running depletion.
    BillResponse replay = payLinesWithKey(bill.id(), List.of(lineId), key);
    assertThat(replay.status()).isEqualTo("PAID");
    assertThat(stockOfIngredient(ingredientId))
        .isEqualTo(1_000 - 25); // unchanged — no double deplete
  }

  // ---------------------------------------------------------------------------
  // (f) atomicity: a failure AFTER depletion would run rolls back the ingredient stock too
  // ---------------------------------------------------------------------------

  @Test
  void aSaleFailureAfterDepletionWouldRunRollsBackIngredientStock() throws Exception {
    UUID ingredientId = createIngredient("Atomic", 1_000, 10L, "IDR");
    UUID itemId = createItem("Atomic Item", 40_000L);
    putRecipe(itemId, List.of(new RecipeLineInput(ingredientId, null, 50)));

    // OrderWriter.checkout order of operations: stock deduction -> ingredient depletion -> record
    // sale -> capturePayment (where an insufficient tender is validated and throws). By the time
    // this throws, depleteForLines() has already executed inside the SAME REQUIRES_NEW tx.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "atomic-fail-001",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 1_000L)); // far less than the total due

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("less than the amount due");

    // Whole REQUIRES_NEW checkout tx rolled back -- ingredient stock reverted to its pre-sale
    // value.
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000);
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
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
                        BUSINESS_ID, name, "g", unitCostMinor, currency, initialStock))
                .id());
  }

  private UUID createGroup(UUID itemId) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            modifierService
                .createGroup(
                    itemId,
                    new CreateModifierGroupRequest(
                        BUSINESS_ID, "Options", "SINGLE", false, 0, 1, 0))
                .id());
  }

  private UUID createOption(UUID groupId, String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            modifierService
                .createOption(groupId, new CreateModifierOptionRequest(BUSINESS_ID, name, 0L, 0))
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

  private BillResponse payLinesWithKey(UUID billId, List<UUID> lineIds, String idempotencyKey)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.payBill(
                billId, new PayBillRequest(null, null, lineIds, idempotencyKey, null)));
  }

  private int stockOfIngredient(UUID ingredientId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
