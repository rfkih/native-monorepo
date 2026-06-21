package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance tests for Phase 3 modifier / 86-ing checkout integration.
 *
 * <p>Verifies:
 *
 * <ol>
 *   <li>Modifier price delta flows into the line total and order subtotal/breakdown.
 *   <li>Ordering an 86'd item is rejected with a 400.
 *   <li>Ordering an unavailable modifier option is rejected.
 *   <li>Selecting an option from another menu item is rejected.
 *   <li>Required modifier group missing a selection is rejected.
 *   <li>max_select enforcement.
 *   <li>Receipt carries modifier snapshots.
 *   <li>Tenancy isolation on modifier tables.
 * </ol>
 */
@SpringBootTest
class ModifierCheckoutTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;
  @Autowired private OrderService orderService;

  /**
   * Worked example: Nasi Goreng IDR 15 000, "Size" SINGLE group with "Large" +5 000. Order 2 qty.
   *
   * <pre>
   *   unitPrice      = 15 000
   *   modifierDelta  =  5 000
   *   effectiveUnit  = 20 000
   *   lineTotal      = 20 000 × 2 = 40 000
   *   subtotal       = 40 000
   *   SC 5%          =  2 000
   *   taxBase        = 42 000 (SC in tax base = true for demo tenant)
   *   PB1 10%        =  4 200
   *   grandTotal     = 40 000 + 2 000 + 4 200 = 46 200
   * </pre>
   */
  @Test
  void modifierDeltaFlowsIntoLineTotalAndBreakdown() throws Exception {
    UUID itemId = createNasiGoreng();
    UUID groupId = createSizeGroup(itemId, false, 0, 1);
    UUID largeOptionId = createOption(groupId, "Large", 5_000L);

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "mod-delta-001",
            List.of(new OrderLineRequest(itemId, 2, List.of(largeOptionId))));

    CheckoutResult result = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    OrderLineResponse line = result.order().lines().get(0);

    // Line total = (15_000 + 5_000) × 2 = 40_000
    assertThat(line.unitPriceMinor()).isEqualTo(15_000L); // base price
    assertThat(line.lineTotalMinor()).isEqualTo(40_000L);

    // Modifier snapshot on the line.
    assertThat(line.modifiers()).hasSize(1);
    assertThat(line.modifiers().get(0).nameSnapshot()).isEqualTo("Large");
    assertThat(line.modifiers().get(0).priceDeltaMinor()).isEqualTo(5_000L);
    assertThat(line.modifiers().get(0).optionId()).isEqualTo(largeOptionId);

    // Breakdown: subtotal = 40_000 (modifier-adjusted)
    assertThat(result.order().breakdown().subtotalMinor()).isEqualTo(40_000L);
    // SC 5% of 40_000 = 2_000; PB1 10% of (40_000 + 2_000) = 4_200; grand = 46_200
    assertThat(result.order().breakdown().serviceChargeMinor()).isEqualTo(2_000L);
    assertThat(result.order().breakdown().taxMinor()).isEqualTo(4_200L);
    assertThat(result.order().breakdown().grandTotalMinor()).isEqualTo(46_200L);
    assertThat(result.order().totalMinor()).isEqualTo(46_200L);
  }

  @Test
  void checkoutRejectsUnavailableItem() throws Exception {
    UUID itemId = createNasiGoreng();

    // 86 the item.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          modifierService.markItemUnavailable(itemId);
          return null;
        });

    CheckoutRequest req =
        new CheckoutRequest(BUSINESS_ID, "86-item-001", List.of(new OrderLineRequest(itemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available (86'd)");
  }

  @Test
  void checkoutRejectsUnavailableModifierOption() throws Exception {
    UUID itemId = createNasiGoreng();
    UUID groupId = createSizeGroup(itemId, false, 0, 1);
    UUID largeOptionId = createOption(groupId, "Large", 5_000L);

    // 86 the option.
    TenantContext.callAs(
        TENANT_A, ACTOR, () -> modifierService.markOptionUnavailable(largeOptionId));

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "86-option-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(largeOptionId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available");
  }

  @Test
  void checkoutRejectsOptionFromAnotherItem() throws Exception {
    UUID itemA = createNasiGoreng();
    UUID itemB =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Mie Goreng", "MAIN", 12_000L, "IDR"))
                    .id());

    // Create a modifier group on itemB.
    UUID groupIdB = createSizeGroupFor(itemB, false, 0, 1);
    UUID optionFromB = createOption(groupIdB, "Large", 3_000L);

    // Try to use itemB's option on itemA — must be rejected.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "xitem-option-001",
            List.of(new OrderLineRequest(itemA, 1, List.of(optionFromB))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong to menu item");
  }

  @Test
  void checkoutEnforcesRequiredModifierGroupMinSelect() throws Exception {
    UUID itemId = createNasiGoreng();
    // Required group, min=1.
    createSizeGroup(itemId, true, 1, 1);

    // No option selected — required group unsatisfied.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID, "required-group-001", List.of(new OrderLineRequest(itemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required modifier group");
  }

  @Test
  void checkoutEnforcesMaxSelectPerGroup() throws Exception {
    UUID itemId = createNasiGoreng();
    UUID groupId = createSizeGroup(itemId, false, 0, 1); // maxSelect = 1
    UUID smallId = createOption(groupId, "Small", -2_000L);
    UUID largeId = createOption(groupId, "Large", 5_000L);

    // Try to select 2 options from a max=1 group.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "maxselect-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(smallId, largeId))));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Too many selections");
  }

  @Test
  void receiptCarriesModifierSnapshotsAfterOptionIsUnavailableLater() throws Exception {
    UUID itemId = createNasiGoreng();
    UUID groupId = createSizeGroup(itemId, false, 0, 1);
    UUID largeId = createOption(groupId, "Large", 5_000L);

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "receipt-snap-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(largeId))));

    CheckoutResult result = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));
    assertThat(result.order().lines().get(0).modifiers()).hasSize(1);
    assertThat(result.order().lines().get(0).modifiers().get(0).nameSnapshot()).isEqualTo("Large");
    assertThat(result.order().lines().get(0).modifiers().get(0).priceDeltaMinor())
        .isEqualTo(5_000L);

    // Now 86 the option — the receipt is still intact (snapshot in order_line_modifier).
    TenantContext.callAs(TENANT_A, ACTOR, () -> modifierService.markOptionUnavailable(largeId));

    // Re-read via idempotency — snapshot must still be on the line.
    CheckoutResult idempotent =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));
    assertThat(idempotent.created()).isFalse();
    assertThat(idempotent.order().lines().get(0).modifiers().get(0).nameSnapshot())
        .isEqualTo("Large");
  }

  @Test
  void modifierTenancyIsolation() throws Exception {
    // Modifier group created under TENANT_A is invisible to TENANT_B.
    UUID itemA = createNasiGoreng();
    UUID groupAId = createSizeGroup(itemA, false, 0, 1);

    // TENANT_B tries to read modifier groups for itemA — RLS makes itemA invisible.
    // findGroupsWithOptions returns empty (item not visible under tenant B's scope).
    // We verify via TenantContext.callAs that no groups leak across tenants.
    java.util.List<id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse> bGroups =
        TenantContext.callAs(TENANT_B, ACTOR, () -> modifierService.findGroupsWithOptions(itemA));

    // Under tenant B's RLS, the group (company_id = TENANT_A) is invisible.
    assertThat(bGroups).isEmpty();
    // (groupAId will not appear even if queried, because the RLS policy on
    // menu_item_modifier_group uses company_id = app.current_tenant.)
  }

  @Test
  void orderWithNoModifiersStillWorksWithBackwardCompatibleRequest() throws Exception {
    UUID itemId = createNasiGoreng();

    // Use the 2-arg OrderLineRequest (no selectedOptionIds) — backward compat.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID, "no-modifier-001", List.of(new OrderLineRequest(itemId, 2)));

    CheckoutResult result = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().lines().get(0).lineTotalMinor()).isEqualTo(30_000L);
    assertThat(result.order().lines().get(0).modifiers()).isEmpty();
    // Phase 2 breakdown: 30_000 sub | SC 5% = 1_500 | PB1 10% of 31_500 = 3_150 | total 34_650
    assertThat(result.order().totalMinor()).isEqualTo(34_650L);
  }

  @Test
  void negativeModifierDeltaReducesLineTotal() throws Exception {
    UUID itemId = createNasiGoreng();
    UUID groupId = createSizeGroup(itemId, false, 0, 1);
    UUID smallId = createOption(groupId, "Small", -5_000L); // discount variant

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "neg-delta-001",
            List.of(new OrderLineRequest(itemId, 1, List.of(smallId))));

    CheckoutResult result = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.checkout(req));

    // lineTotal = (15_000 - 5_000) × 1 = 10_000
    assertThat(result.order().lines().get(0).lineTotalMinor()).isEqualTo(10_000L);
  }

  // ---- helpers ----

  private UUID createNasiGoreng() throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR"))
                .id());
  }

  private UUID createSizeGroup(UUID itemId, boolean required, int min, int max) throws Exception {
    return createSizeGroupFor(itemId, required, min, max);
  }

  private UUID createSizeGroupFor(UUID itemId, boolean required, int min, int max)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService
                .createGroup(
                    itemId,
                    new CreateModifierGroupRequest(
                        BUSINESS_ID, "Size", "SINGLE", required, min, max, 0))
                .id());
  }

  private UUID createOption(UUID groupId, String name, long delta) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService
                .createOption(groupId, new CreateModifierOptionRequest(BUSINESS_ID, name, delta, 0))
                .id());
  }
}
