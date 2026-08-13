package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.charge.domain.ChargeStatus;
import id.co.nativeapp.payment.charge.domain.WebhookRejectedException;
import id.co.nativeapp.payment.charge.service.ChargeWriter;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort;
import id.co.nativeapp.payment.charge.service.WebhookService;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.service.SettingsService;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The Midtrans webhook end-to-end at the service layer (ADR 0045): constant-time signature
 * authentication against the merchant's own key (tampered → uniform reject; forged tenant → same
 * uniform reject via RLS-invisible settings), exactly-once settlement (duplicate notification
 * absorbs, one {@code PaymentChargeSucceeded} outbox row), and every park-don't-drop case (unknown
 * order, amount mismatch, late settlement after a local cancel) landing in {@code error_log}
 * WITHOUT capturing.
 */
@SpringBootTest
class WebhookAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID OUTLET = UUID.fromString("b555d5b8-e17b-4990-a7dd-2c4be199d7a6");
  private static final String SERVER_KEY = "SB-Mid-server-webhook-secret";

  @Autowired private WebhookService webhookService;
  @Autowired private ChargeWriter chargeWriter;
  @Autowired private SettingsService settingsService;

  @BeforeEach
  void resetTablesAndSeedGatewaySettings() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE payment_charge");
      st.execute("TRUNCATE TABLE payment_settings");
      st.execute("TRUNCATE TABLE outbox");
      st.execute("TRUNCATE TABLE error_log");
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(ownerRequest()));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            settingsService.upsertCompanyDefault(
                new UpsertSettingsRequest(
                    "GATEWAY", "MIDTRANS", "SANDBOX", SERVER_KEY, null, null, null)));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static MockHttpServletRequest ownerRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", "owner");
    return request;
  }

  /** Mints a QR_ISSUED charge directly through the writer (no PSP stub needed). */
  private UUID issueCharge(long amountMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          ChargeWriter.CreateOutcome outcome =
              chargeWriter.createInitiated(
                  "restaurant",
                  UUID.randomUUID(),
                  null,
                  OUTLET,
                  null,
                  amountMinor,
                  "IDR",
                  "wh:" + UUID.randomUUID());
          chargeWriter.markQrIssued(
              outcome.charge().getId(),
              new QrisGatewayPort.QrCreated(
                  "QR-PAYLOAD", null, "mt-txn-0", Instant.now().plusSeconds(900)));
          return outcome.charge().getId();
        });
  }

  private ChargeStatus statusOf(UUID chargeId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT status FROM payment_charge WHERE id = ?")) {
      ps.setObject(1, chargeId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return ChargeStatus.valueOf(rs.getString(1));
      }
    }
  }

  private String notification(UUID chargeId, long amountMinor, String transactionStatus) {
    return notification("n-" + chargeId, amountMinor + ".00", transactionStatus, SERVER_KEY);
  }

  private String notification(
      String orderId, String gross, String transactionStatus, String signingKey) {
    String statusCode = "200";
    String signature = sha512Hex(orderId + statusCode + gross + signingKey);
    return """
        {"order_id":"%s","status_code":"%s","gross_amount":"%s","signature_key":"%s",
         "transaction_status":"%s","transaction_id":"mt-txn-web","currency":"IDR"}
        """
        .formatted(orderId, statusCode, gross, signature, transactionStatus);
  }

  @Test
  void aSignedSettlementCapturesExactlyOnceAcrossDuplicates() throws Exception {
    UUID chargeId = issueCharge(185_000L);

    webhookService.handleMidtrans(
        UUID.fromString(TENANT), notification(chargeId, 185_000L, "settlement"));
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.SUCCEEDED);
    assertThat(outboxCount()).isEqualTo(1);

    // Midtrans retries / duplicates: absorbed, still exactly one event.
    webhookService.handleMidtrans(
        UUID.fromString(TENANT), notification(chargeId, 185_000L, "settlement"));
    assertThat(outboxCount()).isEqualTo(1);
    assertThat(errorLogCount()).isZero();
  }

  @Test
  void aTamperedSignatureIsRejectedUniformlyAndNothingMoves() throws Exception {
    UUID chargeId = issueCharge(185_000L);
    String tampered =
        notification("n-" + chargeId, "185000.00", "settlement", "wrong-key-entirely");

    assertThatThrownBy(() -> webhookService.handleMidtrans(UUID.fromString(TENANT), tampered))
        .isInstanceOf(WebhookRejectedException.class);
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.QR_ISSUED);
    assertThat(outboxCount()).isZero();
  }

  @Test
  void aForgedTenantIsRejectedExactlyLikeABadSignature() throws Exception {
    UUID chargeId = issueCharge(185_000L);
    // A correctly SIGNED body, but claimed for a tenant with no settings row: RLS makes the
    // settings invisible under the provisional bind — the same uniform rejection, no oracle.
    assertThatThrownBy(
            () ->
                webhookService.handleMidtrans(
                    UUID.randomUUID(), notification(chargeId, 185_000L, "settlement")))
        .isInstanceOf(WebhookRejectedException.class);
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.QR_ISSUED);
    assertThat(outboxCount()).isZero();
  }

  @Test
  void anUnknownOrderParksInsteadOfCapturing() throws Exception {
    webhookService.handleMidtrans(
        UUID.fromString(TENANT),
        notification("n-" + UUID.randomUUID(), "50000.00", "settlement", SERVER_KEY));
    assertThat(outboxCount()).isZero();
    assertThat(errorLogSources()).contains("payment.psp-webhook.unknown-order");
  }

  @Test
  void anAmountMismatchParksInsteadOfCapturing() throws Exception {
    UUID chargeId = issueCharge(185_000L);
    // Signed correctly — but for a DIFFERENT gross than our charge (e.g. a client-tampered charge
    // request would produce exactly this shape). Park, never capture.
    webhookService.handleMidtrans(
        UUID.fromString(TENANT), notification("n-" + chargeId, "1.00", "settlement", SERVER_KEY));
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.QR_ISSUED);
    assertThat(outboxCount()).isZero();
    assertThat(errorLogSources()).contains("payment.psp-webhook.amount-mismatch");
  }

  @Test
  void aLateSettlementAfterALocalCancelParksForAHumanRefund() throws Exception {
    UUID chargeId = issueCharge(42_000L);
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          chargeWriter.markCanceled(chargeId);
          return null;
        });

    webhookService.handleMidtrans(
        UUID.fromString(TENANT), notification(chargeId, 42_000L, "settlement"));

    // Money moved at the PSP with no local capture: parked, stays CANCELED, no event.
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.CANCELED);
    assertThat(outboxCount()).isZero();
    assertThat(errorLogSources()).contains("payment.psp-webhook.late-settlement");
  }

  @Test
  void anExpireNotificationExpiresALiveChargeWithoutAnEvent() throws Exception {
    UUID chargeId = issueCharge(42_000L);
    webhookService.handleMidtrans(
        UUID.fromString(TENANT), notification(chargeId, 42_000L, "expire"));
    assertThat(statusOf(chargeId)).isEqualTo(ChargeStatus.EXPIRED);
    assertThat(outboxCount()).isZero();
    assertThat(errorLogCount()).isZero();
  }

  private static String sha512Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static long outboxCount() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT COUNT(*) FROM outbox");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long errorLogCount() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT COUNT(*) FROM error_log");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static java.util.List<String> errorLogSources() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT source FROM error_log");
        ResultSet rs = ps.executeQuery()) {
      java.util.List<String> sources = new java.util.ArrayList<>();
      while (rs.next()) {
        sources.add(rs.getString(1));
      }
      return sources;
    }
  }
}
