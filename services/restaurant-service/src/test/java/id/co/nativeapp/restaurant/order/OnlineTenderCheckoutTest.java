package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.channel.dto.CreateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.dto.SalesChannelResponse;
import id.co.nativeapp.restaurant.channel.dto.UpdateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.service.SalesChannelWriter;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0036 Phase B2 — the {@code ONLINE} tender threaded through {@code OrderWriter}
 * (checkout/pay-parked). Proves the four rejection rules ((a) missing channel, (b) unknown/
 * inactive channel, (c) gift-card pairing forbidden, (d) loyalty-points redemption forbidden) and
 * the happy path: synchronous capture (unlike QRIS/CARD, never PENDING), the channel snapshot
 * lands on both {@code sale.channel_code} and {@code payment.channel_code}, and {@code
 * cash_collected_minor} stays {@code NULL} (ONLINE is not cash — the register-close cash window
 * must never include it).
 */
@SpringBootTest
class OnlineTenderCheckoutTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("77777777-7777-7777-7777-777777777701");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private SalesChannelWriter salesChannelWriter;

  private UUID createMenuItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(
                        BUSINESS_ID, "Nasi Goreng", "MAIN", priceMinor, "IDR"))
                .id());
  }

  private String createActiveChannel(String code) throws Exception {
    SalesChannelResponse response =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                salesChannelWriter.create(
                    new CreateSalesChannelRequest(code, code + " Delivery")));
    return response.code();
  }

  private void deactivateChannel(String code) throws Exception {
    SalesChannelResponse existing =
        TenantContext.callAs(TENANT, ACTOR, salesChannelWriter::list).stream()
            .filter(c -> c.code().equals(code))
            .findFirst()
            .orElseThrow();
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            salesChannelWriter.update(
                existing.id(), new UpdateSalesChannelRequest(null, false)));
  }

  private CheckoutRequest onlineCheckout(
      UUID itemId,
      String idempotencyKey,
      String channelCode,
      UUID giftCardId,
      Long giftCardRedeemMinor,
      UUID loyaltyMemberId,
      Long loyaltyRedeemPoints) {
    return new CheckoutRequest(
        BUSINESS_ID,
        idempotencyKey,
        List.of(new OrderLineRequest(itemId, 1)),
        new PaymentRequest(TenderType.ONLINE, null),
        null,
        null,
        null,
        null,
        loyaltyMemberId,
        loyaltyRedeemPoints,
        giftCardId,
        giftCardRedeemMinor,
        null,
        null,
        channelCode);
  }

  // -----------------------------------------------------------------------
  // Happy path
  // -----------------------------------------------------------------------

  @Test
  void onlineCheckoutCapturesSynchronouslyAndThreadsTheChannelOntoSaleAndPayment()
      throws Exception {
    UUID itemId = createMenuItem(20_000L);
    String channel = createActiveChannel("gofood");

    CheckoutRequest req =
        onlineCheckout(
            itemId, "online-happy-" + UUID.randomUUID(), channel, null, null, null, null);

    CheckoutResult result = TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    // Synchronous capture — never AWAITING_PAYMENT like a PENDING QRIS/CARD tender.
    assertThat(order.status()).isEqualTo("COMPLETED");
    assertThat(order.saleId()).isNotNull();
    assertThat(order.payment()).isNotNull();
    assertThat(order.payment().status()).isEqualTo("CAPTURED");
    assertThat(order.payment().tenderType()).isEqualTo("ONLINE");
    assertThat(order.payment().providerPending()).isFalse();

    assertThat(saleChannelCodeAsAdmin(order.saleId())).isEqualTo("GOFOOD");
    assertThat(paymentChannelCodeAsAdmin(order.payment().paymentId())).isEqualTo("GOFOOD");
    assertThat(cashCollectedMinorAsAdmin(order.saleId()))
        .as("ONLINE is not cash — must stay out of the register-close cash window")
        .isNull();
  }

  @Test
  void onlineTenderThroughPayParkedAlsoThreadsTheChannel() throws Exception {
    UUID itemId = createMenuItem(15_000L);
    String channel = createActiveChannel("grabfood");

    UUID orderId =
        TenantContext.callAs(
                TENANT,
                ACTOR,
                () ->
                    orderService.park(
                        new ParkOrderRequest(
                            BUSINESS_ID,
                            "online-park-" + UUID.randomUUID(),
                            List.of(new OrderLineRequest(itemId, 1)))))
            .order()
            .orderId();

    PayParkedRequest payRequest =
        new PayParkedRequest(
            new PaymentRequest(TenderType.ONLINE, null), null, null, null, null, null, channel);

    OrderResponse paid =
        TenantContext.callAs(TENANT, ACTOR, () -> orderService.payParked(orderId, payRequest));

    assertThat(paid.status()).isEqualTo("COMPLETED");
    assertThat(paid.payment().tenderType()).isEqualTo("ONLINE");
    assertThat(saleChannelCodeAsAdmin(paid.saleId())).isEqualTo("GRABFOOD");
  }

  // -----------------------------------------------------------------------
  // (a) channelCode required
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithNoChannelCodeIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    CheckoutRequest req =
        onlineCheckout(
            itemId, "online-nochannel-" + UUID.randomUUID(), null, null, null, null, null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("channelCode");
  }

  // -----------------------------------------------------------------------
  // (b) channel must exist AND be active
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithAnUnknownChannelIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    CheckoutRequest req =
        onlineCheckout(
            itemId, "online-unknown-" + UUID.randomUUID(), "NOSUCHCHANNEL", null, null, null, null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or inactive sales channel");
  }

  @Test
  void onlineTenderWithADeactivatedChannelIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    String channel = createActiveChannel("shopeefood");
    deactivateChannel(channel);

    CheckoutRequest req =
        onlineCheckout(
            itemId, "online-inactive-" + UUID.randomUUID(), channel, null, null, null, null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or inactive sales channel");
  }

  // -----------------------------------------------------------------------
  // (c) no gift-card redemption alongside ONLINE
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithAGiftCardIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    String channel = createActiveChannel("gojek");

    CheckoutRequest req =
        onlineCheckout(
            itemId,
            "online-giftcard-" + UUID.randomUUID(),
            channel,
            UUID.randomUUID(),
            5_000L,
            null,
            null);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gift card");
  }

  // -----------------------------------------------------------------------
  // (d) no loyalty-points redemption alongside ONLINE
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithALoyaltyPointsRedemptionIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    String channel = createActiveChannel("maxim");

    CheckoutRequest req =
        onlineCheckout(
            itemId,
            "online-loyalty-" + UUID.randomUUID(),
            channel,
            null,
            null,
            UUID.randomUUID(),
            500L);

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loyalty points");
  }

  // -----------------------------------------------------------------------
  // Helpers — raw JDBC over the admin (BYPASSRLS) connection.
  // -----------------------------------------------------------------------

  private String saleChannelCodeAsAdmin(UUID saleId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT channel_code FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private String paymentChannelCodeAsAdmin(UUID paymentId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT channel_code FROM payment WHERE id = ?")) {
      ps.setObject(1, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private Long cashCollectedMinorAsAdmin(UUID saleId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT cash_collected_minor FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long value = rs.getLong(1);
        return rs.wasNull() ? null : value;
      }
    }
  }
}
