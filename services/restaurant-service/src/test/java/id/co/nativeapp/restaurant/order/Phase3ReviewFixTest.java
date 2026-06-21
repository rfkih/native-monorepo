package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
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
 * Integration tests for Phase 3 catalog review findings.
 *
 * <p>Covers:
 *
 * <ol>
 *   <li>Back-fill correctness: pre-V6 {@code menu_item} rows get one {@code menu_category} per
 *       distinct {@code (company_id, business_id, category)} tuple; no cross-tenant leak.
 *   <li>Quote negative-delta rejection (quote and checkout behave identically — finding #1/#7).
 *   <li>Duplicate option ids are rejected (finding #4).
 *   <li>business_id is derived from the loaded MenuItem/ModifierGroup (finding #5).
 *   <li>Non-required min_select and SINGLE type are enforced at checkout (finding #6).
 * </ol>
 */
@SpringBootTest
class Phase3ReviewFixTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUSINESS_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;
  @Autowired private OrderService orderService;

  // -------------------------------------------------------------------------
  // Back-fill correctness (finding #2 + #3)
  // -------------------------------------------------------------------------

  /**
   * Simulates the V6 back-fill: inserts menu_item rows for two tenants, each with distinct category
   * strings, using the admin (BYPASSRLS) connection — as the migration would do before V6 ran. Then
   * invokes the back-fill SQL directly and asserts:
   *
   * <ul>
   *   <li>Exactly one {@code menu_category} per distinct {@code (company_id, business_id,
   *       category)} tuple.
   *   <li>Each item's {@code category_id} points to the category owned by its OWN tenant.
   *   <li>No cross-tenant category leak.
   * </ul>
   *
   * <p>Because Flyway already ran V6 on the fresh container (empty tables), we manually insert
   * pre-back-fill rows via the admin connection and then invoke the idempotent back-fill procedure
   * extracted as a DO block. The procedure matches V6's back-fill logic exactly.
   */
  @Test
  void backFillCreatesOneCategoryPerDistinctTupleAndNoTenantLeak() throws Exception {
    UUID itemIdA1 = UUID.randomUUID();
    UUID itemIdA2 = UUID.randomUUID();
    UUID itemIdB1 = UUID.randomUUID();

    // Insert pre-V6 menu_item rows via admin connection (bypasses RLS and mimics pre-migration
    // state). We insert without category_id so the back-fill must set it.
    String insertItem =
        "INSERT INTO menu_item (id, business_id, name, category, price_minor, currency, "
            + "active, available, created_at, created_by, updated_at, updated_by, version,"
            + " company_id) VALUES ";
    String now = "now()";
    executeAsAdmin(
        insertItem
            + "('"
            + itemIdA1
            + "', '"
            + BUSINESS_A
            + "', 'Nasi Goreng', 'MAIN', 15000, 'IDR', true, true, "
            + now
            + ", 'test', "
            + now
            + ", 'test', 0, '"
            + TENANT_A
            + "'),"
            + "('"
            + itemIdA2
            + "', '"
            + BUSINESS_A
            + "', 'Mie Goreng', 'SNACK', 8000, 'IDR', true, true, "
            + now
            + ", 'test', "
            + now
            + ", 'test', 0, '"
            + TENANT_A
            + "'),"
            + "('"
            + itemIdB1
            + "', '"
            + BUSINESS_B
            + "', 'Nasi Putih', 'MAIN', 5000, 'IDR', true, true, "
            + now
            + ", 'test', "
            + now
            + ", 'test', 0, '"
            + TENANT_B
            + "')");

    // Run the back-fill DO block (same logic as V6 migration).
    executeAsAdmin(
        "DO $$ "
            + "DECLARE "
            + "  rec RECORD; "
            + "  new_cat_id UUID; "
            + "BEGIN "
            + "  SET LOCAL row_security = off; "
            + "  FOR rec IN "
            + "    SELECT DISTINCT company_id, business_id, category "
            + "      FROM menu_item "
            + "     WHERE category IS NOT NULL "
            + "       AND category_id IS NULL "
            + "     ORDER BY company_id, business_id, category "
            + "  LOOP "
            + "    EXECUTE format('SET LOCAL app.current_tenant = %L', rec.company_id); "
            + "    INSERT INTO menu_category "
            + "        (id, business_id, name, display_order, active, "
            + "         created_at, created_by, updated_at, updated_by, version, company_id) "
            + "    VALUES "
            + "        (gen_random_uuid(), rec.business_id, rec.category, 0, TRUE, "
            + "         now(), 'system-migration', now(), 'system-migration', 0, rec.company_id) "
            + "    RETURNING id INTO new_cat_id; "
            + "    SET LOCAL row_security = off; "
            + "    UPDATE menu_item SET category_id = new_cat_id "
            + "     WHERE company_id = rec.company_id "
            + "       AND business_id = rec.business_id "
            + "       AND category = rec.category; "
            + "  END LOOP; "
            + "END $$");

    // Assert: 3 distinct tuples → 3 categories (TENANT_A:BUSINESS_A:MAIN,
    // TENANT_A:BUSINESS_A:SNACK, TENANT_B:BUSINESS_B:MAIN).
    assertThat(rowCountAsAdmin("menu_category")).isEqualTo(3L);

    // Each item's category_id must point to a category with the SAME company_id.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {

      // All items should now have a non-null category_id.
      ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item WHERE category_id IS NULL "
                  + "AND id IN ('"
                  + itemIdA1
                  + "','"
                  + itemIdA2
                  + "','"
                  + itemIdB1
                  + "')");
      rs.next();
      assertThat(rs.getLong(1)).as("no item should have null category_id after back-fill").isZero();

      // No category should have a different company_id than its linked items.
      ResultSet crossTenantRs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item mi "
                  + "JOIN menu_category mc ON mi.category_id = mc.id "
                  + "WHERE mi.company_id <> mc.company_id "
                  + "AND mi.id IN ('"
                  + itemIdA1
                  + "','"
                  + itemIdA2
                  + "','"
                  + itemIdB1
                  + "')");
      crossTenantRs.next();
      assertThat(crossTenantRs.getLong(1))
          .as("no item should point to a cross-tenant category")
          .isZero();

      // TENANT_B's item must NOT point to TENANT_A's category.
      ResultSet tenantBCatRs =
          st.executeQuery(
              "SELECT mc.company_id FROM menu_item mi "
                  + "JOIN menu_category mc ON mi.category_id = mc.id "
                  + "WHERE mi.id = '"
                  + itemIdB1
                  + "'");
      tenantBCatRs.next();
      assertThat(tenantBCatRs.getString("company_id"))
          .as("Tenant B item must point to a Tenant B category")
          .isEqualTo(TENANT_B);
    }
  }

  // -------------------------------------------------------------------------
  // Quote negative-delta rejection (finding #1 + #7)
  // -------------------------------------------------------------------------

  /**
   * A modifier option whose delta brings effectiveUnit below zero must be rejected at QUOTE time
   * with the same error as checkout (quote/checkout parity). Item price = 5 000; modifier delta =
   * −10 000; effectiveUnit = −5 000 → rejected.
   */
  @Test
  void quoteRejectsNegativeEffectiveUnitPrice() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Cheap Item", "MAIN", 5_000L, "IDR"))
                    .id());

    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(null, "Discount", "SINGLE", false, 0, 1, 0))
                    .id());

    UUID bigDiscountOptionId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(null, "Big Discount", -10_000L, 0))
                    .id());

    // Quote with modifier that makes effectiveUnit = 5000 - 10000 = -5000.
    QuoteRequest quoteReq =
        new QuoteRequest(
            BUSINESS_A, List.of(new OrderLineRequest(itemId, 1, List.of(bigDiscountOptionId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.quote(quoteReq)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Effective unit price must be >= 0");

    // The same cart also fails at checkout — identical guard.
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_A,
            "neg-unit-chk-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(bigDiscountOptionId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(checkoutReq)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Effective unit price must be >= 0");
  }

  // -------------------------------------------------------------------------
  // Duplicate option id rejection (finding #4)
  // -------------------------------------------------------------------------

  /**
   * Sending the same option id twice in {@code selectedOptionIds} must be rejected with a 400
   * rather than silently double-charging the delta.
   */
  @Test
  void checkoutRejectsDuplicateOptionIds() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_A, "Nasi Goreng", "MAIN", 15_000L, "IDR"))
                    .id());

    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(null, "Size", "MULTI", false, 0, 3, 0))
                    .id());

    UUID optionId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(null, "Large", 3_000L, 0))
                    .id());

    // Send the same option id twice — should be rejected.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_A,
            "dup-option-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(optionId, optionId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate modifier option ids");
  }

  /** Duplicate option ids in a quote are also rejected (quote/checkout parity). */
  @Test
  void quoteRejectsDuplicateOptionIds() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Mie Goreng", "MAIN", 12_000L, "IDR"))
                    .id());

    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(null, "Extra", "MULTI", false, 0, 3, 0))
                    .id());

    UUID optionId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(groupId, new CreateModifierOptionRequest(null, "Egg", 2_000L, 0))
                    .id());

    QuoteRequest req =
        new QuoteRequest(
            BUSINESS_A, List.of(new OrderLineRequest(itemId, 1, List.of(optionId, optionId))));

    assertThatThrownBy(() -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.quote(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate modifier option ids");
  }

  // -------------------------------------------------------------------------
  // business_id derived from entity (finding #5)
  // -------------------------------------------------------------------------

  /**
   * The modifier group's {@code business_id} must match the parent menu item's {@code business_id},
   * not whatever the caller sends in the request body. We verify by passing {@code null} as
   * business_id in the request (the field is now ignored), and confirm the saved group's
   * business_id equals the item's.
   */
  @Test
  void modifierGroupBusinessIdDerivedFromMenuItem() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Soup", "MAIN", 10_000L, "IDR"))
                    .id());

    // Pass null businessId — the service must derive it from the menu item.
    ModifierGroupResponse group =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService.createGroup(
                    itemId,
                    new CreateModifierGroupRequest(null, "Spice", "SINGLE", false, 0, 1, 0)));

    assertThat(group.id()).isNotNull();
    // Verify via admin that the saved business_id equals BUSINESS_A (the item's business).
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT business_id FROM menu_item_modifier_group WHERE id = '"
                    + group.id()
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getObject("business_id", java.util.UUID.class))
          .as("group.business_id should be derived from the menu item, not the request body")
          .isEqualTo(BUSINESS_A);
    }
  }

  // -------------------------------------------------------------------------
  // Non-required min_select and SINGLE enforcement (finding #6)
  // -------------------------------------------------------------------------

  /**
   * A non-required group with {@code min_select = 1} must be enforced at checkout — even though the
   * group is not marked {@code required = true}.
   */
  @Test
  void checkoutEnforcesNonRequiredGroupMinSelect() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Burger", "MAIN", 25_000L, "IDR"))
                    .id());

    // NOT required but min_select = 1 — should still be enforced.
    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(null, "Sauce", "MULTI", false, 1, 2, 0))
                    .id());

    // Create at least one option so the group is choosable.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService.createOption(
                groupId, new CreateModifierOptionRequest(null, "Ketchup", 0L, 0)));

    // No options selected → min_select = 1 not met → must be rejected.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_A, "nonreq-minselect-001", List.of(new OrderLineRequest(itemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires at least 1 selection(s)");
  }

  /**
   * A SINGLE-selection-type group must reject more than one selection, even if {@code max_select}
   * were set to 2 (SINGLE overrides — at most 1 allowed).
   */
  @Test
  void checkoutEnforcesSingleSelectionType() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Pizza", "MAIN", 50_000L, "IDR"))
                    .id());

    // SINGLE group — only 1 selection allowed regardless of max_select field.
    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(null, "Size", "SINGLE", false, 0, 1, 0))
                    .id());

    UUID smallId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(null, "Small", -5_000L, 0))
                    .id());
    UUID largeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(null, "Large", 5_000L, 1))
                    .id());

    // Select 2 options from a SINGLE group → must be rejected.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_A,
            "single-type-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(smallId, largeId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SINGLE-select");
  }

  /** Non-required group with min_select = 0 and no selections: valid (should not throw). */
  @Test
  void checkoutAllowsOptionalGroupWithZeroMinSelectAndNoSelection() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_A, "Salad", "MAIN", 20_000L, "IDR"))
                    .id());

    // Optional group, min_select = 0 — zero selections should be fine.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService.createGroup(
                itemId,
                new CreateModifierGroupRequest(null, "Dressing", "SINGLE", false, 0, 1, 0)));

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_A, "optional-zero-minselect-001", List.of(new OrderLineRequest(itemId, 1)));

    CheckoutResult result = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));
    assertThat(result.created()).isTrue();
    assertThat(result.order().lines().get(0).modifiers()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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

  private void executeAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(sql);
    }
  }
}
