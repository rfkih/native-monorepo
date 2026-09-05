package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Concurrency proof for {@code IngredientDepletionWriter} (ADR 0050 phase A), mirroring the {@code
 * IngredientStocktakeConcurrencyTest} harness idiom (a {@link CyclicBarrier} to maximise the race).
 *
 * <p>Two DIFFERENT menu items — with recipes that reference the SAME two ingredients but list them
 * in OPPOSITE natural insertion order — are checked out CONCURRENTLY on the same outlet. The
 * writer's documented contract is: net-per-ingredient totals are applied via single-row {@code
 * GREATEST(stock_qty - qty, 0)} UPDATEs in ASCENDING ingredient-UUID order, regardless of a
 * recipe's own line order — so two concurrent sales sharing two ingredients always lock those rows
 * in the SAME order and can never deadlock. This test proves: both checkouts complete without error
 * (no deadlock, no lock-order abort) and the final depletion is the exact sum of both sales'
 * contributions — no lost update, no double-count.
 *
 * <p>It also proves the V42 per-day usage aggregate accumulates ADDITIVELY under the same race:
 * both concurrent sales UPSERT the same {@code ingredient_stock_day} rows ({@code ON CONFLICT DO
 * UPDATE qty_used = existing + EXCLUDED}), so the day's {@code terpakai} figure must equal the
 * exact same per-ingredient sum as the depletion — no lost update on the read-modify-write.
 */
@SpringBootTest
class IngredientDepletionConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-recipe-race@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private IngredientService ingredientService;
  @Autowired private MenuService menuService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;

  @Test
  void twoConcurrentCheckoutsSharingTwoIngredientsInOppositeRecipeOrderNeverDeadlockAndSumExactly()
      throws Exception {
    UUID ingredient1 = createIngredient("Ing-1", 1_000_000);
    UUID ingredient2 = createIngredient("Ing-2", 1_000_000);

    UUID itemX = createItem("Item-X");
    UUID itemY = createItem("Item-Y");

    // Natural insertion order deliberately differs between the two recipes; the writer's ascending
    // -UUID TreeMap ordering makes both concurrent depletions lock the two ingredient rows in the
    // SAME order regardless of which recipe lists which ingredient first.
    putRecipe(
        itemX,
        List.of(
            new RecipeLineInput(ingredient1, null, 3), new RecipeLineInput(ingredient2, null, 5)));
    putRecipe(
        itemY,
        List.of(
            new RecipeLineInput(ingredient2, null, 7), new RecipeLineInput(ingredient1, null, 11)));

    CyclicBarrier startLine = new CyclicBarrier(2);
    List<CheckoutResult> results = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Runnable checkoutX =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS); // both fire together to maximise the race
              results.add(checkout(itemX, 4, "conc-recipe-x"));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      Runnable checkoutY =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS);
              results.add(checkout(itemY, 6, "conc-recipe-y"));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      pool.submit(checkoutX);
      pool.submit(checkoutY);
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // No deadlock, no lock-order abort, no other error — both checkouts must complete cleanly.
    assertThat(errors).isEmpty();
    assertThat(results).hasSize(2);
    assertThat(results).allSatisfy(r -> assertThat(r.created()).isTrue());

    // ingredient1: (3 * 4 from X) + (11 * 6 from Y) = 12 + 66 = 78.
    assertThat(stockOfIngredient(ingredient1)).isEqualTo(1_000_000 - 78);
    // ingredient2: (5 * 4 from X) + (7 * 6 from Y) = 20 + 42 = 62.
    assertThat(stockOfIngredient(ingredient2)).isEqualTo(1_000_000 - 62);

    // V42: the day's usage UPSERT accumulated the exact same per-ingredient sum — both concurrent
    // sales added to the same ingredient_stock_day rows with no lost update.
    assertThat(usageOfIngredient(ingredient1)).isEqualTo(78);
    assertThat(usageOfIngredient(ingredient2)).isEqualTo(62);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createIngredient(String name, int initialStock) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingredientService
                .create(
                    new CreateIngredientRequest(
                        BUSINESS_ID, name, "g", null, 10L, "IDR", initialStock))
                .id());
  }

  private UUID createItem(String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS_ID, name, "MAIN", 15_000L, "IDR"))
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

  private CheckoutResult checkout(UUID itemId, int qty, String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS_ID, idempotencyKey, List.of(new OrderLineRequest(itemId, qty)))));
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

  /** Total {@code qty_used} across all usage-day rows for this ingredient (a single day here). */
  private long usageOfIngredient(UUID ingredientId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(SUM(qty_used), 0) FROM ingredient_stock_day"
                    + " WHERE ingredient_id = '"
                    + ingredientId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
