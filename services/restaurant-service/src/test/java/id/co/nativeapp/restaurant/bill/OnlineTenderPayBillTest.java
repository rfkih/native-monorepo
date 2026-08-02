package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.channel.dto.CreateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.dto.SalesChannelResponse;
import id.co.nativeapp.restaurant.channel.dto.UpdateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.service.SalesChannelWriter;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
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
 * ADR 0036 Phase B2 — the {@code ONLINE} tender threaded through {@code BillWriter.payBill}.
 * Mirrors {@link id.co.nativeapp.restaurant.order.OnlineTenderCheckoutTest}'s coverage for the
 * bill flow: the (a) missing-channel and (b) unknown/inactive-channel rejections, and the happy
 * path threading the channel onto {@code sale.channel_code}. {@code PayBillRequest} carries no
 * loyalty/gift-card fields (bills do not support that redemption), so rules (c)/(d) are vacuously
 * satisfied on this path — see {@code BillWriter.validateOnlineTenderAndNormalize}'s javadoc.
 *
 * <p>Unlike the order checkout path, {@code BillWriter.payBill} never creates a {@code payment}
 * row for ANY tender (CASH included) — only the {@code sale.tender_type}/{@code channel_code} are
 * stamped. ONLINE stays consistent with that existing behaviour (no new payment-row creation
 * introduced here).
 */
@SpringBootTest
class OnlineTenderPayBillTest extends PostgresRlsTestBase {

  private static final String TENANT = "88888888-8888-8888-8888-888888888888";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("88888888-8888-8888-8888-888888888801");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;
  @Autowired private SalesChannelWriter salesChannelWriter;

  private UUID createMenuItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS_ID, "Es Teh", "BEVERAGE", priceMinor, "IDR"))
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
        () -> salesChannelWriter.update(existing.id(), new UpdateSalesChannelRequest(null, false)));
  }

  private UUID openBillWithOneLine(UUID itemId) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS_ID, null, "Table 9")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, 1)))));
    return opened.id();
  }

  // -----------------------------------------------------------------------
  // Happy path
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderPaysTheBillAndThreadsTheChannelOntoTheSale() throws Exception {
    UUID itemId = createMenuItem(25_000L);
    String channel = createActiveChannel("gofood");
    UUID billId = openBillWithOneLine(itemId);

    BillResponse paid =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                billService.payBill(
                    billId,
                    new PayBillRequest(
                        new PaymentRequest(TenderType.ONLINE, null), null, null, null, channel)));

    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(paid.saleId()).isNotNull();
    assertThat(saleChannelCodeAsAdmin(paid.saleId())).isEqualTo("GOFOOD");
    assertThat(saleTenderTypeAsAdmin(paid.saleId())).isEqualTo("ONLINE");
  }

  // -----------------------------------------------------------------------
  // (a) channelCode required
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithNoChannelCodeIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    UUID billId = openBillWithOneLine(itemId);

    PayBillRequest request =
        new PayBillRequest(new PaymentRequest(TenderType.ONLINE, null), null, null, null, null);

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> billService.payBill(billId, request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("channelCode");
  }

  // -----------------------------------------------------------------------
  // (b) channel must exist AND be active
  // -----------------------------------------------------------------------

  @Test
  void onlineTenderWithAnUnknownChannelIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    UUID billId = openBillWithOneLine(itemId);

    PayBillRequest request =
        new PayBillRequest(
            new PaymentRequest(TenderType.ONLINE, null), null, null, null, "NOSUCHCHANNEL");

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> billService.payBill(billId, request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or inactive sales channel");
  }

  @Test
  void onlineTenderWithADeactivatedChannelIsRejected() throws Exception {
    UUID itemId = createMenuItem(10_000L);
    String channel = createActiveChannel("shopeefood");
    deactivateChannel(channel);
    UUID billId = openBillWithOneLine(itemId);

    PayBillRequest request =
        new PayBillRequest(new PaymentRequest(TenderType.ONLINE, null), null, null, null, channel);

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> billService.payBill(billId, request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or inactive sales channel");
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

  private String saleTenderTypeAsAdmin(UUID saleId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT tender_type FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }
}
