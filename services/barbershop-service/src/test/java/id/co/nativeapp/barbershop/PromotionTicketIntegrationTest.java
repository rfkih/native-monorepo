package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.barbershop.promotion.service.PromotionAdminService;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.QuoteRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.events.AvroSerde;
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
 * End-to-end acceptance test for the Phase-3 promotions engine (ADR 0026) over the real barbershop
 * quote/checkout/capture flow, a fresh (no seeded tax/SC rule — the V1 seed is scoped only to
 * tenant {@code 11111111-...}) tenant so the math is exact. Ported from carwash-service's identical
 * port of restaurant-service's {@code PromotionCheckoutIntegrationTest}, adapted to barbershop's
 * MANDATORY barber staff profile (ADR 0024).
 *
 * <ul>
 *   <li>quote reports the resolved coupon (status + the per-rule detail), never redeems it;
 *   <li>CASH checkout applies the engine's collapsed discount to the breakdown AND the emitted
 *       {@code SaleRecorded}'s {@code discount_minor} (zero Avro schema change — same aggregate
 *       field); {@code applied_promotion} rows are written in the SAME transaction with {@code
 *       sale_id} already set (sale id == ticket id, ADR 0023 decision 2, preserved by ADR 0024);
 *   <li>DIGITAL checkout writes {@code applied_promotion} rows with {@code sale_id = NULL}; capture
 *       stamps it;
 *   <li>{@code barbershop_ticket.coupon_id} is stamped;
 *   <li>an idempotent replay (same idempotency key) does NOT re-run the engine or re-redeem the
 *       coupon (no second {@code applied_promotion} row, {@code redeemed_count} stays 1).
 * </ul>
 */
@SpringBootTest
class PromotionTicketIntegrationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT = "99999999-9999-9999-9999-999999999998";
  private static final String ACTOR = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("99999999-9999-9999-9999-999999999902");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private CatalogService catalogService;
  @Autowired private TicketService ticketService;
  @Autowired private PromotionAdminService promotionAdminService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private void grantBarbershop() {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, BARBERSHOP, true));
  }

  private UUID createBarber() throws Exception {
    return TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi", null, true)))
        .id();
  }

  @Test
  void quoteReportsCouponAndCashCheckoutCollapsesItIntoTheDiscountWithAnAuditTrailAndNoDoubleRedeem()
      throws Exception {
    grantBarbershop();
    UUID serviceId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    catalogService.createService(
                        new CatalogItemCreateRequest(OUTLET, "Haircut", null, 10_000L, "IDR", null)))
            .id();
    UUID barberId = createBarber();

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
                            // requiresCoupon — the rule fires ONLY via the code.
                            true,
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

    List<TicketLineInput> lines = List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1));

    // -----------------------------------------------------------------------
    // 1. Quote reports the coupon — no side effects, no redemption.
    // -----------------------------------------------------------------------
    PriceBreakdownResponse quote =
        TenantContext.callAs(
            TENANT, ACTOR, () -> ticketService.quote(new QuoteRequest(OUTLET, lines, null, "save10")));

    assertThat(quote.couponStatus()).isEqualTo("APPLIED");
    assertThat(quote.appliedPromotions()).hasSize(1);
    assertThat(quote.appliedPromotions().get(0).amountMinor()).isEqualTo(1_000L);
    assertThat(quote.discountMinor()).isEqualTo(1_000L);
    assertThat(quote.grandTotalMinor()).isEqualTo(9_000L);
    assertThat(redeemedCountAsAdmin(couponId)).as("quote never redeems").isZero();

    // -----------------------------------------------------------------------
    // 2. CASH checkout collapses the coupon's deduction into discount_minor.
    // -----------------------------------------------------------------------
    String idemKey = "promo-checkout-" + UUID.randomUUID();
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "chair-1",
            barberId,
            null,
            lines,
            new PaymentRequest(TenderType.CASH, 10_000L),
            "SAVE10");

    CheckoutResult first = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(checkoutReq));

    assertThat(first.created()).isTrue();
    TicketResponse ticket = first.ticket();
    UUID ticketId = ticket.ticketId();
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(9_000L); // 10,000 - 1,000
    assertThat(ticket.saleId()).isEqualTo(ticketId); // ADR 0023 decision 2

    // SaleRecorded carries the collapsed discount (zero Avro schema change).
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'SaleRecorded' AND aggregate_id = ?"
                + " ORDER BY occurred_at DESC LIMIT 1",
            ticketId.toString());
    GenericRecord decoded =
        AvroSerde.deserialize(
            (byte[]) outboxRow.get("payload"),
            id.co.nativeapp.barbershop.ticket.messaging.TicketSaleRecordedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(9_000L);
    assertThat(decoded.get("discount_minor")).isEqualTo(1_000L);

    // applied_promotion audit row: same tx, sale_id already stamped (cash path -> sale_id ==
    // ticket_id).
    List<Map<String, Object>> rows = appliedPromotionRows(ticketId);
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("rule_id")).hasToString(ruleId.toString());
    assertThat(row.get("coupon_id")).hasToString(couponId.toString());
    assertThat(((Number) row.get("amount_minor")).longValue()).isEqualTo(1_000L);
    assertThat(row.get("sale_id")).hasToString(ticketId.toString());

    // barbershop_ticket.coupon_id is stamped.
    assertThat(couponIdColumnAsAdmin(ticketId)).isEqualTo(couponId);

    // The coupon is now redeemed exactly once.
    assertThat(redeemedCountAsAdmin(couponId)).isEqualTo(1);

    // -----------------------------------------------------------------------
    // 3. Idempotent replay — same idempotencyKey — must NOT re-run the engine or re-redeem.
    // -----------------------------------------------------------------------
    CheckoutResult retry = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(checkoutReq));

    assertThat(retry.created()).isFalse();
    assertThat(retry.ticket().ticketId()).isEqualTo(ticketId);
    assertThat(redeemedCountAsAdmin(couponId)).as("replay does not double-redeem").isEqualTo(1);
    assertThat(appliedPromotionRows(ticketId)).as("replay writes no second audit row").hasSize(1);
  }

  @Test
  void digitalCheckoutWritesNullSaleIdThenCaptureStampsIt() throws Exception {
    grantBarbershop();
    UUID serviceId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    catalogService.createService(
                        new CatalogItemCreateRequest(OUTLET, "Haircut", null, 40_000L, "IDR", null)))
            .id();
    UUID barberId = createBarber();

    UUID ruleId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    promotionAdminService
                        .createRule(
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
                                false,
                                LocalDate.of(2026, 1, 1),
                                null))
                        .id());

    List<TicketLineInput> lines = List.of(new TicketLineInput(ItemType.SERVICE, serviceId, 1));
    String idemKey = "promo-digital-" + UUID.randomUUID();
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            OUTLET,
            idemKey,
            "chair-2",
            barberId,
            null,
            lines,
            new PaymentRequest(TenderType.QRIS, null),
            null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> ticketService.checkout(checkoutReq));
    UUID ticketId = result.ticket().ticketId();
    assertThat(result.ticket().saleId()).isNull();

    List<Map<String, Object>> pendingRows = appliedPromotionRows(ticketId);
    assertThat(pendingRows).hasSize(1);
    assertThat(pendingRows.get(0).get("sale_id")).as("digital tender defers sale_id").isNull();
    assertThat(pendingRows.get(0).get("rule_id")).hasToString(ruleId.toString());

    TenantContext.callAs(TENANT, ACTOR, () -> ticketService.capture(ticketId));

    List<Map<String, Object>> capturedRows = appliedPromotionRows(ticketId);
    assertThat(capturedRows).hasSize(1);
    assertThat(capturedRows.get(0).get("sale_id"))
        .as("capture stamps sale_id == ticket_id (ADR 0023 decision 2)")
        .hasToString(ticketId.toString());
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

  private UUID couponIdColumnAsAdmin(UUID ticketId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT coupon_id FROM barbershop_ticket WHERE id = ?")) {
      ps.setObject(1, ticketId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  /**
   * Reads {@code applied_promotion} rows over an admin/BYPASSRLS connection — the table carries
   * {@code FORCE ROW LEVEL SECURITY} (V3), so the RLS-scoped {@code jdbcTemplate} (no tenant GUC
   * bound outside a {@code @Transactional} call) would see zero rows here.
   */
  private List<Map<String, Object>> appliedPromotionRows(UUID ticketId) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT rule_id, coupon_id, amount_minor, sale_id FROM applied_promotion WHERE"
                    + " ticket_id = ?")) {
      ps.setObject(1, ticketId);
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
