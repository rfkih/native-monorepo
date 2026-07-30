package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.barbershop.promotion.service.PromotionAdminService;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.QuoteRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
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
 * TicketTenancyIsolationTest}'s tenant-pair idiom and carwash-service's {@code
 * PromotionTenancyIsolationTest} (ported to the ticket flow, ADR 0024).
 *
 * <ul>
 *   <li>tenant A creates a {@code requires_coupon} rule + a coupon;
 *   <li>tenant B quoting with tenant A's coupon code gets {@code couponStatus == "INVALID"} — the
 *       {@code coupon}/{@code promo_rule} JOIN is RLS-scoped, so tenant A's coupon is invisible to
 *       tenant B's session;
 *   <li>tenant B's admin rule listing never includes tenant A's rule.
 * </ul>
 */
@SpringBootTest
class PromotionTenancyIsolationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "barber-a";
  private static final String ACTOR_B = "barber-b";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void tenantBCannotUseOrSeeTenantAsCouponOrRule() throws Exception {
    grantBarbershop(TENANT_A);
    grantBarbershop(TENANT_B);

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

    // Tenant B: its own service, in its own outlet — attempts to quote using tenant A's code.
    UUID serviceB =
        TenantContext.callAs(
                TENANT_B,
                ACTOR_B,
                () ->
                    catalogService.createService(
                        new CatalogItemCreateRequest(
                            OUTLET, "Tenant B Haircut", null, 10_000L, "IDR", null)))
            .id();

    PriceBreakdownResponse quoteB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                ticketService.quote(
                    new QuoteRequest(
                        OUTLET,
                        List.of(new TicketLineInput(ItemType.SERVICE, serviceB, 1)),
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
              CatalogItemResponse serviceA =
                  catalogService.createService(
                      new CatalogItemCreateRequest(
                          OUTLET, "Tenant A Haircut", null, 10_000L, "IDR", null));
              return ticketService.quote(
                  new QuoteRequest(
                      OUTLET,
                      List.of(new TicketLineInput(ItemType.SERVICE, serviceA.id(), 1)),
                      null,
                      "TENANT-A-CODE"));
            });
    assertThat(quoteA.couponStatus()).isEqualTo("APPLIED");
    assertThat(quoteA.discountMinor()).isEqualTo(1_000L);
  }

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, BARBERSHOP, true));
  }
}
