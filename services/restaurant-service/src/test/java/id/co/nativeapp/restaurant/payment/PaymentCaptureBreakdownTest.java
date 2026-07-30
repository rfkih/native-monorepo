package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.service.PromotionAdminService;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Review fix (W1, Phase 3 promotions parity — ADR 0026): a digital-tender (QRIS/CARD) capture must
 * carry the SAME Phase-2 price breakdown a cash sale does, not a {@code null} one. Before this fix
 * {@code PaymentCaptureWriter} built its {@link
 * id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand RecordSaleCommand} with the 6-arg
 * (no-breakdown) constructor unconditionally, so every digital-tender {@code SaleRecorded} lost the
 * discount AND tax/service-charge legs (finance falls back to {@code subtotal == amount_minor}).
 *
 * <p>Mirrors {@link id.co.nativeapp.restaurant.promotion.PromotionCheckoutIntegrationTest}'s cash-
 * path assertions, but over the digital checkout → capture flow.
 */
@SpringBootTest
class PaymentCaptureBreakdownTest extends PostgresRlsTestBase {

  private static final String TENANT = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("99999999-9999-9999-9999-999999999901");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService captureService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void digitalCaptureWithACouponDiscountReconstructsTheFullBreakdown() throws Exception {
    UUID menuItemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Es Teh", "BEVERAGE", 10_000L, "IDR"))
                    .id());

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
                            true, // requiresCoupon
                            LocalDate.of(2026, 1, 1),
                            null)))
            .id();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            promotionAdminService.createCoupon(
                new CouponCreateRequest("SAVE10", ruleId, 1, null)));

    List<OrderLineRequest> lines = List.of(new OrderLineRequest(menuItemId, 1));
    String idemKey = "digital-promo-" + UUID.randomUUID();
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            lines,
            new PaymentRequest(TenderType.QRIS, null),
            null,
            null,
            null,
            "SAVE10");

    CheckoutResult checkout =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq));

    // 10,000 - 1,000 discount; no tax/SC seeded for this fresh tenant.
    assertThat(checkout.order().totalMinor()).isEqualTo(9_000L);
    assertThat(checkout.order().saleId()).as("digital tender defers the sale").isNull();

    UUID paymentId = checkout.order().payment().paymentId();

    // ------------------------------------------------------------------
    // Capture — must reconstruct the breakdown and pass it through, not null.
    // ------------------------------------------------------------------
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    GenericRecord decoded = latestSaleRecorded();
    assertThat(decoded.get("amount_minor")).isEqualTo(9_000L);
    assertThat(decoded.get("subtotal_minor"))
        .as("subtotal leg must be populated, not null")
        .isEqualTo(10_000L);
    assertThat(decoded.get("discount_minor"))
        .as("discount leg must equal the engine's coupon discount")
        .isEqualTo(1_000L);
    assertThat(decoded.get("service_charge_minor")).isEqualTo(0L);
    assertThat(decoded.get("tax_minor")).isEqualTo(0L);
  }

  @Test
  void digitalCaptureFallsBackToNoBreakdownOnAReconciliationMismatch() throws Exception {
    UUID menuItemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Kopi Susu", "BEVERAGE", 20_000L, "IDR"))
                    .id());

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
                            true,
                            LocalDate.of(2026, 1, 1),
                            null)))
            .id();

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            promotionAdminService.createCoupon(
                new CouponCreateRequest("SAVE10B", ruleId, 1, null)));

    List<OrderLineRequest> lines = List.of(new OrderLineRequest(menuItemId, 1));
    String idemKey = "digital-mismatch-" + UUID.randomUUID();
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID,
            idemKey,
            lines,
            new PaymentRequest(TenderType.QRIS, null),
            null,
            null,
            null,
            "SAVE10B");

    CheckoutResult checkout =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq));
    assertThat(checkout.order().totalMinor()).isEqualTo(18_000L); // 20,000 - 2,000

    // Simulate the priced inputs having drifted between checkout and capture (e.g. an admin
    // retroactively edited what a rule discounted) by tampering with the persisted audit row's
    // amount directly — reconstruction will then sum to a discount that does NOT reconcile to the
    // order's already-fixed total.
    UUID orderId = checkout.order().orderId();
    tamperAppliedPromotionAmountAsAdmin(orderId, 500L);

    UUID paymentId = checkout.order().payment().paymentId();
    TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));

    // The safety guard must have fired: the SaleRecorded amount is UNCHANGED (money never
    // distorted) but every Phase-2 breakdown leg is null — exactly the pre-fix fallback shape.
    GenericRecord decoded = latestSaleRecorded();
    assertThat(decoded.get("amount_minor"))
        .as("the grand total actually charged is never altered by the safety guard")
        .isEqualTo(18_000L);
    assertThat(decoded.get("subtotal_minor")).isNull();
    assertThat(decoded.get("discount_minor")).isNull();
    assertThat(decoded.get("service_charge_minor")).isNull();
    assertThat(decoded.get("tax_minor")).isNull();
  }

  private GenericRecord latestSaleRecorded() {
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'SaleRecorded' AND aggregate_type ="
                + " 'sale' ORDER BY occurred_at DESC LIMIT 1");
    return AvroSerde.deserialize((byte[]) outboxRow.get("payload"), SaleRecordedSchema.schema());
  }

  /**
   * Directly overwrites the {@code applied_promotion} row's {@code amount_minor} for {@code
   * orderId} over an admin/BYPASSRLS connection — the table carries {@code FORCE ROW LEVEL
   * SECURITY} (V16), so the RLS-scoped repository path cannot be used here even with the tenant
   * bound (there is no application mutation for this — it stands in for "the priced inputs changed
   * between checkout and capture").
   */
  private void tamperAppliedPromotionAmountAsAdmin(UUID orderId, long newAmountMinor)
      throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE applied_promotion SET amount_minor = ? WHERE order_id = ?")) {
      ps.setLong(1, newAmountMinor);
      ps.setObject(2, orderId);
      int updated = ps.executeUpdate();
      assertThat(updated).as("exactly one applied_promotion row to tamper with").isEqualTo(1);
    }
  }
}
