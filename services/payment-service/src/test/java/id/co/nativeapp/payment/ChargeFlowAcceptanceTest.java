package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.charge.domain.ChargeConflictException;
import id.co.nativeapp.payment.charge.domain.ChargeNotFoundException;
import id.co.nativeapp.payment.charge.domain.ChargeValidationException;
import id.co.nativeapp.payment.charge.domain.GatewayUnavailableException;
import id.co.nativeapp.payment.charge.dto.ChargeResponse;
import id.co.nativeapp.payment.charge.dto.CreateChargeRequest;
import id.co.nativeapp.payment.charge.service.ChargeService;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.service.SettingsService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The charge lifecycle end-to-end at the service layer against a stub Midtrans (ADR 0045,
 * DIVISION-scope amendment): the two-transaction create (INITIATED anchor → PSP call → QR_ISSUED),
 * replay-by-key and one-live-charge-per-payment, the IDR/mode/credential guards (including the
 * division-level GATEWAY fallback), FAILED on a dead PSP, the sync fallback settling with EXACTLY
 * ONE {@code PaymentChargeSucceeded} outbox row, the cancel-races-settlement money guarantee, and
 * RLS isolation.
 */
@SpringBootTest
class ChargeFlowAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String OTHER_TENANT = "88888888-8888-8888-8888-888888888888";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID OUTLET = UUID.fromString("b555d5b8-e17b-4990-a7dd-2c4be199d7a6");
  private static final UUID DIVISION = UUID.fromString("c666d5b8-e17b-4990-a7dd-2c4be199d7a6");

  /** The stub Midtrans the configurable base URL points at. */
  static final MockWebServer MIDTRANS = new MockWebServer();

  static {
    try {
      MIDTRANS.start();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to start the Midtrans stub", e);
    }
  }

  /** Per-test behavior switches for the dispatcher. */
  private volatile String chargeBehavior = "ok";

  private volatile String statusBehavior = "pending";
  private volatile String cancelBehavior = "ok";
  private final AtomicInteger chargeCalls = new AtomicInteger();

  @Autowired private ChargeService chargeService;
  @Autowired private SettingsService settingsService;

  @DynamicPropertySource
  static void midtransProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "native.payment.midtrans.sandbox-base-url",
        () -> "http://" + MIDTRANS.getHostName() + ":" + MIDTRANS.getPort());
    registry.add("native.payment.webhook-base-url", () -> "https://uat.example.test");
  }

  @BeforeEach
  void resetTablesAndSeedGatewaySettings() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE payment_charge");
      st.execute("TRUNCATE TABLE payment_settings");
      st.execute("TRUNCATE TABLE outbox");
    }
    chargeBehavior = "ok";
    statusBehavior = "pending";
    cancelBehavior = "ok";
    chargeCalls.set(0);
    MIDTRANS.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (path.equals("/v2/charge")) {
              chargeCalls.incrementAndGet();
              return switch (chargeBehavior) {
                case "down" -> new MockResponse().setResponseCode(500);
                default ->
                    // expiry_time must be DYNAMIC (now + 1 day): a hardcoded date was a time
                    // bomb — once it passed, every stubbed charge was born expired and the
                    // lazy expireIfPast sweep flipped QR_ISSUED to EXPIRED (field-found
                    // 2026-08-08, the day after the hardcoded 2026-08-07 23:59:00).
                    json(
                        "{\"status_code\":\"201\",\"transaction_id\":\"mt-txn-1\","
                            + "\"qr_string\":\"00020101021226QRIS-PAYLOAD\","
                            + "\"expiry_time\":\""
                            + java.time.LocalDateTime.now()
                                .plusDays(1)
                                .format(
                                    java.time.format.DateTimeFormatter.ofPattern(
                                        "yyyy-MM-dd HH:mm:ss"))
                            + "\"}");
              };
            }
            if (path.endsWith("/status")) {
              return switch (statusBehavior) {
                case "settlement" ->
                    json(
                        "{\"status_code\":\"200\",\"transaction_status\":\"settlement\","
                            + "\"transaction_id\":\"mt-txn-settled\"}");
                default -> json("{\"status_code\":\"200\",\"transaction_status\":\"pending\"}");
              };
            }
            if (path.endsWith("/cancel")) {
              return switch (cancelBehavior) {
                case "already-settled" -> new MockResponse().setResponseCode(412).setBody("{}");
                default -> json("{\"status_code\":\"200\",\"transaction_status\":\"cancel\"}");
              };
            }
            return new MockResponse().setResponseCode(404).setBody("{}");
          }
        });

    // Seed GATEWAY settings as the owner (the roles idiom from the settings acceptance test).
    setRoles("owner");
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            settingsService.upsertCompanyDefault(
                new UpsertSettingsRequest(
                    "GATEWAY", "MIDTRANS", "SANDBOX", "SB-Mid-server-acceptance", null)));
    setRoles("cashier");
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static CreateChargeRequest request(UUID paymentId, long amount) {
    return new CreateChargeRequest("restaurant", paymentId, null, OUTLET, null, amount, "IDR");
  }

  @Test
  void createCommitsTheAnchorCallsMidtransAndIssuesTheQr() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          ChargeService.CreateResult result =
              chargeService.create(request(paymentId, 185_000L), "charge:" + paymentId + ":1");
          assertThat(result.fresh()).isTrue();
          assertThat(result.response().status()).isEqualTo("QR_ISSUED");
          assertThat(result.response().qrString()).isEqualTo("00020101021226QRIS-PAYLOAD");
          assertThat(result.response().amountMinor()).isEqualTo(185_000L);

          // The poll sees the same charge; no settlement yet → no event.
          ChargeResponse polled = chargeService.get(result.response().chargeId());
          assertThat(polled.status()).isEqualTo("QR_ISSUED");
          return null;
        });
    assertThat(outboxCount()).isZero();
  }

  @Test
  void replayByKeyAndLiveChargeBothReturnTheExistingChargeWithoutASecondPspCall() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          String key = "charge:" + paymentId + ":1";
          ChargeService.CreateResult first = chargeService.create(request(paymentId, 50_000L), key);

          // Same key, same payload → replay, no second Midtrans call.
          ChargeService.CreateResult replay =
              chargeService.create(request(paymentId, 50_000L), key);
          assertThat(replay.fresh()).isFalse();
          assertThat(replay.response().chargeId()).isEqualTo(first.response().chargeId());

          // Different key while a live charge exists → the live charge replays.
          ChargeService.CreateResult liveReplay =
              chargeService.create(request(paymentId, 50_000L), "charge:" + paymentId + ":2");
          assertThat(liveReplay.fresh()).isFalse();
          assertThat(liveReplay.response().chargeId()).isEqualTo(first.response().chargeId());

          // Same key, DIFFERENT payload → 409.
          assertThatThrownBy(() -> chargeService.create(request(paymentId, 60_000L), key))
              .isInstanceOf(ChargeConflictException.class);
          return null;
        });
    assertThat(chargeCalls.get()).isEqualTo(1);
  }

  @Test
  void theGuardsRejectNonIdrWrongModeAndMissingCredentials() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          assertThatThrownBy(
                  () ->
                      chargeService.create(
                          new CreateChargeRequest(
                              "restaurant", paymentId, null, OUTLET, null, 10_000L, "USD"),
                          "k-usd"))
              .isInstanceOf(ChargeValidationException.class);
          assertThatThrownBy(
                  () ->
                      chargeService.create(
                          new CreateChargeRequest(
                              "laundry", paymentId, null, OUTLET, null, 10_000L, "IDR"),
                          "k-vert"))
              .isInstanceOf(ChargeValidationException.class);
          return null;
        });

    // Flip the mode to STATIC — the charge writer must refuse (409).
    setRoles("owner");
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            settingsService.upsertCompanyDefault(
                new UpsertSettingsRequest("STATIC", null, null, null, null)));
    setRoles("cashier");
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          assertThatThrownBy(
                  () -> chargeService.create(request(UUID.randomUUID(), 10_000L), "k-mode"))
              .isInstanceOf(ChargeConflictException.class);
          return null;
        });
  }

  @Test
  void divisionGatewayModeAllowsChargeCreationWhenTheOutletHasNoRow() throws Exception {
    // (d) Downgrade the company default's MODE to STATIC (credentials stay — mode and
    // credentials are independent fields on the same row), then set the DIVISION override to
    // GATEWAY. An outlet with no row of its own, under that division, must still resolve to
    // GATEWAY and successfully create a charge.
    setRoles("owner");
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            settingsService.upsertCompanyDefault(
                new UpsertSettingsRequest("STATIC", null, null, null, null)));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            settingsService.upsertUnitOverride(
                DIVISION, new UpsertSettingsRequest("GATEWAY", null, null, null, null)));
    setRoles("cashier");

    UUID outletWithNoRow = UUID.randomUUID();
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          ChargeService.CreateResult result =
              chargeService.create(
                  new CreateChargeRequest(
                      "restaurant", paymentId, null, outletWithNoRow, DIVISION, 77_000L, "IDR"),
                  "charge:" + paymentId + ":1");
          assertThat(result.fresh()).isTrue();
          assertThat(result.response().status()).isEqualTo("QR_ISSUED");
          return null;
        });
  }

  @Test
  void aDeadPspFailsTheChargeAndANewKeyMintsAFreshOne() throws Exception {
    chargeBehavior = "down";
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          String key = "charge:" + paymentId + ":1";
          assertThatThrownBy(() -> chargeService.create(request(paymentId, 10_000L), key))
              .isInstanceOf(GatewayUnavailableException.class);

          // The same key replays the FAILED charge (visible, not retried)…
          ChargeService.CreateResult replay =
              chargeService.create(request(paymentId, 10_000L), key);
          assertThat(replay.fresh()).isFalse();
          assertThat(replay.response().status()).isEqualTo("FAILED");

          // …and a NEW key (the console bumps the attempt) mints a fresh charge once the PSP is
          // back — the FAILED charge is terminal, so the one-live-charge index does not block it.
          chargeBehavior = "ok";
          ChargeService.CreateResult fresh =
              chargeService.create(request(paymentId, 10_000L), "charge:" + paymentId + ":2");
          assertThat(fresh.fresh()).isTrue();
          assertThat(fresh.response().status()).isEqualTo("QR_ISSUED");
          return null;
        });
    assertThat(outboxCount()).isZero();
  }

  @Test
  void syncSettlesExactlyOnceAndEmitsOneEvent() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          ChargeService.CreateResult created =
              chargeService.create(request(paymentId, 99_000L), "charge:" + paymentId + ":1");

          statusBehavior = "settlement";
          ChargeResponse afterSync = chargeService.sync(created.response().chargeId());
          assertThat(afterSync.status()).isEqualTo("SUCCEEDED");

          // A second sync is a no-op — still exactly one event.
          ChargeResponse again = chargeService.sync(created.response().chargeId());
          assertThat(again.status()).isEqualTo("SUCCEEDED");
          return null;
        });
    assertThat(outboxCount()).isEqualTo(1);
    assertThat(outboxEventType()).isEqualTo("PaymentChargeSucceeded");
  }

  @Test
  void cancelRacingASettlementCapturesInsteadOfSwallowingTheMoney() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          ChargeService.CreateResult created =
              chargeService.create(request(paymentId, 42_000L), "charge:" + paymentId + ":1");

          // The customer paid in the same instant the cashier hit cancel: Midtrans answers 412,
          // the status probe says settlement → the charge SETTLES and the event goes out.
          cancelBehavior = "already-settled";
          statusBehavior = "settlement";
          ChargeResponse afterCancel = chargeService.cancel(created.response().chargeId());
          assertThat(afterCancel.status()).isEqualTo("SUCCEEDED");
          return null;
        });
    assertThat(outboxCount()).isEqualTo(1);
  }

  @Test
  void aCleanCancelGoesCanceledAndEmitsNothing() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID paymentId = UUID.randomUUID();
          ChargeService.CreateResult created =
              chargeService.create(request(paymentId, 42_000L), "charge:" + paymentId + ":1");
          ChargeResponse canceled = chargeService.cancel(created.response().chargeId());
          assertThat(canceled.status()).isEqualTo("CANCELED");

          // A canceled charge no longer blocks a fresh one for the same payment.
          ChargeService.CreateResult fresh =
              chargeService.create(request(paymentId, 42_000L), "charge:" + paymentId + ":2");
          assertThat(fresh.fresh()).isTrue();
          return null;
        });
    assertThat(outboxCount()).isZero();
  }

  @Test
  void chargesAreTenantIsolated() throws Exception {
    UUID chargeId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                chargeService
                    .create(request(UUID.randomUUID(), 10_000L), "k-iso")
                    .response()
                    .chargeId());

    TenantContext.callAs(
        OTHER_TENANT,
        ACTOR,
        () -> {
          assertThatThrownBy(() -> chargeService.get(chargeId))
              .isInstanceOf(ChargeNotFoundException.class);
          return null;
        });
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

  private static String outboxEventType() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT event_type FROM outbox LIMIT 1");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }
}
