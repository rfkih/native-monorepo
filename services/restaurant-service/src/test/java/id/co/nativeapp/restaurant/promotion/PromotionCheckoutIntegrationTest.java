package id.co.nativeapp.restaurant.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.service.PromotionAdminService;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end acceptance test for the Phase-3 promotions engine (ADR 0026) over the real
 * quote/checkout flow, a fresh (no seeded tax/SC rule) tenant so the math is exact:
 *
 * <ul>
 *   <li>quote reports the resolved coupon (status + the per-rule detail);
 *   <li>checkout applies the engine's collapsed discount to the breakdown AND the emitted {@code
 *       SaleRecorded}'s {@code discount_minor} (zero Avro schema change — same aggregate field);
 *   <li>{@code applied_promotion} audit rows are written in the SAME transaction as the order/sale;
 *   <li>{@code restaurant_order.coupon_id} is stamped;
 *   <li>an idempotent replay (same idempotency key) does NOT re-run the engine or re-redeem the
 *       coupon (no second {@code applied_promotion} row, {@code redeemed_count} stays 1).
 * </ul>
 */
@SpringBootTest
class PromotionCheckoutIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("99999999-9999-9999-9999-999999999901");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void quoteReportsCouponAndCheckoutCollapsesItIntoTheDiscountWithAnAuditTrailAndNoDoubleRedeem()
      throws Exception {
    UUID menuItemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Es Teh", "BEVERAGE", 10_000L, "IDR");
              return menuService.createItem(req).id();
            });

    UUID ruleId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    promotionAdminService.createRule(
                        new PromoRuleCreateRequest(
                            "10% off",
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
                            null,
                            LocalDate.of(2026, 1, 1),
                            null)))
            .id();

    UUID couponId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    promotionAdminService.createCoupon(
                        new CouponCreateRequest("SAVE10", ruleId, 1, null)))
            .id();

    List<OrderLineRequest> lines = List.of(new OrderLineRequest(menuItemId, 1));

    // -----------------------------------------------------------------------
    // 1. Quote reports the coupon — no side effects, no redemption.
    // -----------------------------------------------------------------------
    PriceBreakdownResponse quote =
        TenantContext.callAs(
            TENANT, ACTOR, () -> orderService.quote(new QuoteRequest(BUSINESS_ID, lines, null, "save10")));

    assertThat(quote.couponStatus()).isEqualTo("APPLIED");
    assertThat(quote.appliedPromotions()).hasSize(1);
    assertThat(quote.appliedPromotions().get(0).amountMinor()).isEqualTo(1_000L);
    assertThat(quote.discountMinor()).isEqualTo(1_000L);
    assertThat(quote.grandTotalMinor()).isEqualTo(9_000L);
    assertThat(redeemedCountAsAdmin(couponId)).as("quote never redeems").isZero();

    // -----------------------------------------------------------------------
    // 2. Checkout collapses the coupon's deduction into discount_minor.
    // -----------------------------------------------------------------------
    String idemKey = "promo-checkout-" + UUID.randomUUID();
    CheckoutRequest checkoutReq =
        new CheckoutRequest(BUSINESS_ID, idemKey, lines, null, null, null, null, "SAVE10");

    CheckoutResult first =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq));

    assertThat(first.created()).isTrue();
    UUID orderId = first.order().orderId();
    assertThat(first.order().totalMinor()).isEqualTo(9_000L); // 10,000 - 1,000, no tax/SC seeded

    // SaleRecorded carries the collapsed discount (zero Avro schema change).
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'SaleRecorded' AND aggregate_type ="
                + " 'sale' ORDER BY occurred_at DESC LIMIT 1");
    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) outboxRow.get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(9_000L);
    assertThat(decoded.get("discount_minor")).isEqualTo(1_000L);

    // applied_promotion audit row: same tx, sale_id already stamped (cash/no-payment path).
    assertThat(appliedPromotionRows(orderId)).hasSize(1);
    Map<String, Object> row = appliedPromotionRows(orderId).get(0);
    assertThat(row.get("rule_id")).hasToString(ruleId.toString());
    assertThat(row.get("coupon_id")).hasToString(couponId.toString());
    assertThat(((Number) row.get("amount_minor")).longValue()).isEqualTo(1_000L);
    assertThat(row.get("sale_id")).isNotNull();

    // restaurant_order.coupon_id is stamped.
    assertThat(couponIdColumnAsAdmin(orderId)).isEqualTo(couponId);

    // The coupon is now redeemed exactly once.
    assertThat(redeemedCountAsAdmin(couponId)).isEqualTo(1);

    // -----------------------------------------------------------------------
    // 3. Idempotent replay — same idempotencyKey — must NOT re-run the engine or re-redeem.
    // -----------------------------------------------------------------------
    CheckoutResult retry =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq));

    assertThat(retry.created()).isFalse();
    assertThat(retry.order().orderId()).isEqualTo(orderId);
    assertThat(redeemedCountAsAdmin(couponId)).as("replay does not double-redeem").isEqualTo(1);
    assertThat(appliedPromotionRows(orderId)).as("replay writes no second audit row").hasSize(1);
  }

  private int redeemedCountAsAdmin(UUID couponId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT redeemed_count FROM coupon WHERE id = ?")) {
      ps.setObject(1, couponId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private UUID couponIdColumnAsAdmin(UUID orderId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT coupon_id FROM restaurant_order WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  /**
   * Reads {@code applied_promotion} rows over an admin/BYPASSRLS connection — the table carries
   * {@code FORCE ROW LEVEL SECURITY} (V16), so the RLS-scoped {@code jdbcTemplate} (no tenant GUC
   * bound outside a {@code @Transactional} call) would see zero rows here.
   */
  private List<Map<String, Object>> appliedPromotionRows(UUID orderId) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT rule_id, coupon_id, amount_minor, sale_id FROM applied_promotion WHERE"
                    + " order_id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new HashMap<>();
          row.put("rule_id", rs.getObject("rule_id"));
          row.put("coupon_id", rs.getObject("coupon_id"));
          row.put("amount_minor", rs.getObject("amount_minor"));
          row.put("sale_id", rs.getObject("sale_id"));
          rows.add(row);
        }
      }
    }
    return rows;
  }
}
