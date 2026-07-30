package id.co.nativeapp.restaurant.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.restaurant.promotion.service.PromotionAdminService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Security review fix (S-1): explicit two-tenant coupon/rule isolation proof for the Phase-3
 * promotions engine (ADR 0026), relying on AUTO-applied RLS — mirrors {@link
 * id.co.nativeapp.restaurant.order.CheckoutTenancyIsolationTest}'s tenant-pair idiom.
 *
 * <ul>
 *   <li>tenant A creates a {@code requires_coupon} rule + a coupon;
 *   <li>tenant B quoting with tenant A's coupon code gets {@code couponStatus == "INVALID"} — the
 *       {@code coupon}/{@code promo_rule} JOIN is RLS-scoped, so tenant A's coupon is invisible to
 *       tenant B's session (indistinguishable from an unknown code — no existence disclosure);
 *   <li>tenant B's admin rule listing never includes tenant A's rule.
 * </ul>
 */
@SpringBootTest
class PromotionTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUSINESS_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PromotionAdminService promotionAdminService;

  @Test
  void tenantBCannotUseOrSeeTenantAsCouponOrRule() throws Exception {
    // Tenant A: a requires_coupon rule + a coupon bound to it.
    UUID ruleIdA =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () ->
                    promotionAdminService.createRule(
                        new PromoRuleCreateRequest(
                            "Tenant A 10% off",
                            "PERCENT_OFF_ORDER",
                            null,
                            null,
                            1_000L,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            true,
                            LocalDate.of(2026, 1, 1),
                            null)))
            .id();

    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            promotionAdminService.createCoupon(
                new CouponCreateRequest("TENANT-A-CODE", ruleIdA, 1, null)));

    // Tenant B: its own item, in its own business — attempts to quote using tenant A's code.
    UUID menuItemB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_B, "Tenant B Item", "MAIN", 10_000L, "IDR"))
                    .id());

    PriceBreakdownResponse quoteB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                orderService.quote(
                    new QuoteRequest(
                        BUSINESS_B,
                        List.of(new OrderLineRequest(menuItemB, 1)),
                        null,
                        "TENANT-A-CODE")));

    // RLS makes tenant A's coupon row invisible to tenant B's session — the code resolves the same
    // as an unknown one, never leaking that it exists under another tenant.
    assertThat(quoteB.couponStatus()).isEqualTo("INVALID");
    assertThat(quoteB.appliedPromotions()).isEmpty();
    assertThat(quoteB.discountMinor())
        .as("no discount from an invisible cross-tenant coupon")
        .isZero();

    // Tenant B's admin rule listing never includes tenant A's rule.
    List<UUID> rulesVisibleToB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                promotionAdminService.listRules(false).stream()
                    .map(PromoRuleResponse::id)
                    .toList());
    assertThat(rulesVisibleToB).doesNotContain(ruleIdA);

    // Baseline: tenant A can see and successfully use its own rule/coupon.
    PriceBreakdownResponse quoteA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID menuItemA =
                  menuService
                      .createItem(
                          new CreateMenuItemRequest(
                              BUSINESS_A, "Tenant A Item", "MAIN", 10_000L, "IDR"))
                      .id();
              return orderService.quote(
                  new QuoteRequest(
                      BUSINESS_A,
                      List.of(new OrderLineRequest(menuItemA, 1)),
                      null,
                      "TENANT-A-CODE"));
            });
    assertThat(quoteA.couponStatus()).isEqualTo("APPLIED");
    assertThat(quoteA.discountMinor()).isEqualTo(1_000L);
  }
}
