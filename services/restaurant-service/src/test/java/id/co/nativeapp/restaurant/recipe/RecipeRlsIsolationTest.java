package id.co.nativeapp.restaurant.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse.Completeness;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Security gate for the recipe feature (ADR 0050 phase A), mirroring {@code
 * IngredientInventoryRlsIsolationTest}: proves the V34 {@code recipe_line} FORCE-RLS policy
 * isolates tenants both through the service layer and against a direct SQL plant, AND that per-sale
 * ingredient depletion can never cross a tenant boundary even in the worst case of a
 * (hypothetically planted) cross-tenant ingredient reference.
 */
@SpringBootTest
class RecipeRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-recipe-a@example.co.id";
  private static final String ACTOR_B = "cashier-recipe-b@example.co.id";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OUTLET_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private IngredientService ingredientService;
  @Autowired private RecipeService recipeService;
  @Autowired private OrderService orderService;

  @Test
  void tenantBCannotReadTenantAsRecipeThroughTheService() throws Exception {
    UUID ingredientId = createIngredientAs(TENANT_A, ACTOR_A, OUTLET_A, "Beef");
    UUID itemId = createItemAs(TENANT_A, ACTOR_A, OUTLET_A, "Rendang");
    putRecipeAs(TENANT_A, ACTOR_A, itemId, List.of(new RecipeLineInput(ingredientId, null, 20)));

    // Tenant A sees its own recipe.
    RecipeResponse asA =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> recipeService.getRecipe(itemId));
    assertThat(asA.lines()).hasSize(1);

    // Tenant B reading the SAME item id is RLS-scoped to empty -- no leakage, and no exception
    // either: the GET endpoint never checks existence (see RecipeController javadoc), an unknown/
    // invisible item simply has no lines.
    RecipeResponse asB =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> recipeService.getRecipe(itemId));
    assertThat(asB.lines()).isEmpty();
    assertThat(asB.unitHppMinor()).isNull();
    assertThat(asB.completeness()).isEqualTo(Completeness.MISSING_COST);
  }

  @Test
  void tenantBCannotReplaceTenantAsRecipe() throws Exception {
    UUID ingredientId = createIngredientAs(TENANT_A, ACTOR_A, OUTLET_A, "Beef");
    UUID itemId = createItemAs(TENANT_A, ACTOR_A, OUTLET_A, "Rendang");

    // Tenant B's item lookup is RLS-scoped -- the item is invisible, so RecipeWriter treats it as
    // unknown (NoSuchElementException), never a silent cross-tenant write.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR_B,
                    () ->
                        recipeService.putRecipe(
                            itemId,
                            new PutRecipeRequest(
                                List.of(new RecipeLineInput(ingredientId, null, 5))))))
        .isInstanceOf(NoSuchElementException.class);

    // Tenant A's recipe (never actually set) stays empty -- no cross-tenant row was planted.
    RecipeResponse asA =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> recipeService.getRecipe(itemId));
    assertThat(asA.lines()).isEmpty();
  }

  @Test
  void directSqlPlantOfARecipeLineClaimingTheWrongTenantIsRejectedByRls() throws Exception {
    UUID ingredientId = createIngredientAs(TENANT_A, ACTOR_A, OUTLET_A, "Beef");
    UUID itemId = createItemAs(TENANT_A, ACTOR_A, OUTLET_A, "Rendang");

    assertThatThrownBy(() -> plantRecipeLineAsWrongTenant(itemId, ingredientId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");
  }

  @Test
  void depletionUnderTenantACannotTouchTenantBsIngredientStock() throws Exception {
    // An ingredient genuinely owned by tenant B.
    UUID ingredientB = createIngredientAs(TENANT_B, ACTOR_B, OUTLET_B, "Foreign");

    UUID itemA = createItemAs(TENANT_A, ACTOR_A, OUTLET_A, "Cross-Tenant Item");

    // Plant a recipe_line as the ADMIN (BYPASSRLS) connection that claims company_id = TENANT_A
    // but references TENANT_B's real ingredient id -- the worst case a bug or attack could ever
    // produce (RecipeWriter itself can never construct this: its ingredient lookup is RLS-scoped
    // to the acting tenant, so it would report "unknown ingredient" for a foreign id).
    plantRecipeLineAsAdmin(TENANT_A, OUTLET_A, itemA, ingredientB, 10);

    // Checking out item A under tenant A must succeed (depletion never blocks a sale) and must NOT
    // touch tenant B's ingredient row: IngredientDepletionWriter's findDepletionRows query joins
    // recipe_line to ingredient WITHOUT an explicit company_id filter, relying entirely on RLS —
    // under tenant A's session the ingredient-table policy hides B's row, so the join yields
    // nothing for that line and it is silently skipped.
    CheckoutResult result =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        OUTLET_A,
                        "cross-tenant-deplete-001",
                        List.of(new OrderLineRequest(itemA, 1)))));
    assertThat(result.created()).isTrue();

    assertThat(stockOfIngredientAsAdmin(ingredientB)).isEqualTo(100);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UUID createItemAs(String tenant, String actor, UUID businessId, String name)
      throws Exception {
    return TenantContext.callAs(
        tenant,
        actor,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(businessId, name, "MAIN", 15_000L, "IDR"))
                .id());
  }

  private UUID createIngredientAs(String tenant, String actor, UUID businessId, String name)
      throws Exception {
    return TenantContext.callAs(
        tenant,
        actor,
        () ->
            ingredientService
                .create(new CreateIngredientRequest(businessId, name, "g", 100L, "IDR", 100))
                .id());
  }

  private void putRecipeAs(String tenant, String actor, UUID itemId, List<RecipeLineInput> lines)
      throws Exception {
    TenantContext.callAs(
        tenant,
        actor,
        () -> {
          recipeService.putRecipe(itemId, new PutRecipeRequest(lines));
          return null;
        });
  }

  private void plantRecipeLineAsWrongTenant(UUID itemId, UUID ingredientId) throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO recipe_line ("
              + "id, business_id, menu_item_id, ingredient_id, modifier_option_id, qty_per_portion,"
              + " created_at, created_by, updated_at, updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + OUTLET_A
              + "', '"
              + itemId
              + "', '"
              + ingredientId
              + "', NULL, 5, now(), 'attacker', now(), 'attacker', 0, '"
              + TENANT_A
              + "')");
    }
  }

  /**
   * Plants a {@code recipe_line} directly as the BYPASSRLS admin role -- setup only, no RLS proof.
   */
  private void plantRecipeLineAsAdmin(
      String companyId, UUID businessId, UUID menuItemId, UUID ingredientId, int qty)
      throws SQLException {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.executeUpdate(
          "INSERT INTO recipe_line ("
              + "id, business_id, menu_item_id, ingredient_id, modifier_option_id, qty_per_portion,"
              + " created_at, created_by, updated_at, updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + businessId
              + "', '"
              + menuItemId
              + "', '"
              + ingredientId
              + "', NULL, "
              + qty
              + ", now(), 'test', now(), 'test', 0, '"
              + companyId
              + "')");
    }
  }

  private int stockOfIngredientAsAdmin(UUID ingredientId) throws SQLException {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_secret");
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
