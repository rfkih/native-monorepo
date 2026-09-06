package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeLineInput;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.service.PostOutboxHook;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Atomicity of the per-day usage bucket on the sale path: because {@code
 * IngredientDepletionWriter.addUsage} runs under {@code Propagation.MANDATORY} in the SAME
 * transaction as the sale + outbox rows, a sale that rolls back must contribute NOTHING to {@code
 * ingredient_usage_day.qty_used} — the usage figure can never drift ahead of committed sales.
 *
 * <p>The assertion is on the USAGE TOTAL rather than on the absence of a ledger row: since V47 the
 * same table also records receipts and manual corrections, and this test's own arrangement
 * legitimately books one — creating the ingredient with opening stock IS a receipt. A row therefore
 * exists before the checkout ever runs, and summing {@code qty_used} is both the precise statement
 * of the invariant and a stricter one than counting rows.
 *
 * <p>This is a genuine write-then-rollback proof, not a trivial never-written one: {@code
 * OrderWriter.checkout} runs {@code ingredientDepletionWriter.depleteForLines} (which UPSERTs the
 * usage row) BEFORE {@code saleWriter.recordInCurrentTx} fires the {@link PostOutboxHook}. The test
 * installs a hook that throws AFTER the outbox write, so the usage row is written and then
 * discarded when the checkout transaction rolls back. Mirrors {@link
 * id.co.nativeapp.restaurant.order.CheckoutAtomicityTest}.
 */
@SpringBootTest
class IngredientUsageAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-usage-atomic@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  static final String BOOM = "forced failure after outbox write (usage atomicity test)";

  @Autowired private IngredientService ingredientService;
  @Autowired private MenuService menuService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;

  @Test
  void aRolledBackSaleLeavesNoUsageRowAndNoStockChange() throws Exception {
    // Arrange: an ingredient with stock, a menu item, and a recipe consuming 3/portion — so a
    // successful checkout WOULD deplete 6 and write a usage row of 6.
    UUID ingredientId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                ingredientService
                    .create(
                        new CreateIngredientRequest(
                            BUSINESS_ID, "Kentang", "g", null, 10L, "IDR", 1_000))
                    .id());
    UUID menuItemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Kentang Goreng", "MAIN", 15_000L, "IDR"))
                    .id());
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          recipeService.putRecipe(
              menuItemId,
              new PutRecipeRequest(List.of(new RecipeLineInput(ingredientId, null, 3))));
          return null;
        });

    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID, "usage-atomic-key", List.of(new OrderLineRequest(menuItemId, 2)));

    // Act: checkout blows up after the outbox write, inside the transaction — after depletion has
    // already UPSERTed the usage row.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BOOM);

    // Assert (over the admin BYPASSRLS connection — the tables are FORCE RLS): the sale rolled
    // back, the usage it had already UPSERTed was discarded with it, and stock is untouched.
    // Nothing drifted ahead of a committed sale. The ledger row itself survives — it belongs to
    // the opening-stock receipt from the arrangement, which never rolled back.
    assertThat(rowCountAsAdmin("sale")).isZero();
    assertThat(usedQtyTotal(ingredientId)).isZero();
    assertThat(stockOfIngredient(ingredientId)).isEqualTo(1_000);
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

  private long usedQtyTotal(UUID ingredientId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(SUM(qty_used), 0) FROM ingredient_usage_day"
                    + " WHERE ingredient_id = '"
                    + ingredientId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
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

  /**
   * Installs a {@link PostOutboxHook} that throws after the outbox write. {@link Primary} so it
   * wins injection into {@code SaleWriter}, keeping this throwing context distinct from the no-op
   * production one (so it never leaks into other {@code @SpringBootTest} classes).
   */
  @TestConfiguration
  static class ThrowingHookConfig {

    @Bean
    @Primary
    PostOutboxHook throwingPostOutboxHookForUsageAtomicity() {
      return (Sale sale) -> {
        throw new IllegalStateException(BOOM);
      };
    }
  }
}
