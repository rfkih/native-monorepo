package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.charge.domain.GatewayUnavailableException;
import id.co.nativeapp.payment.charge.service.MidtransClient;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort.CancelOutcome;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort.GatewayCredentials;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort.GatewayVerification;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort.RemoteStatus;
import id.co.nativeapp.payment.config.PaymentProperties;
import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Midtrans adapter against a stub Core API (no Spring context, no real PSP): request shape
 * (Basic auth from the merchant key, the per-charge {@code X-Override-Notification} callback, qris
 * payment_type), response mapping (qr_string/actions/expiry_time in WIB), the 406-duplicate
 * fallback, status normalization (settlement/capture+accept/expire/deny), and the cancel→412→
 * status disambiguation that protects real money.
 */
class MidtransClientTest {

  private static final UUID COMPANY = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String ORDER_ID = "n-aaaaaaaa-1111-2222-3333-444444444444";

  private MockWebServer midtrans;
  private MidtransClient client;
  private GatewayCredentials credentials;

  @BeforeEach
  void startStub() throws Exception {
    midtrans = new MockWebServer();
    midtrans.start();
    String base = "http://" + midtrans.getHostName() + ":" + midtrans.getPort();
    PaymentProperties properties =
        new PaymentProperties(
            "https://uat.example.test",
            new PaymentProperties.Midtrans(base, base + "/prod", 2000, 5000, 15));
    client = new MidtransClient(properties);
    credentials = new GatewayCredentials("SB-Mid-server-abcdef1234", ProviderEnvironment.SANDBOX);
  }

  @AfterEach
  void stopStub() throws Exception {
    midtrans.shutdown();
  }

  @Test
  void createQrSendsTheContractAndParsesTheResponse() throws Exception {
    midtrans.enqueue(
        json(
            """
            {"status_code":"201","transaction_id":"mt-1","order_id":"%s",
             "qr_string":"00020101021226QRIS-PAYLOAD","expiry_time":"2026-08-07 17:30:00",
             "actions":[{"name":"generate-qr-code","method":"GET",
                         "url":"https://api.sandbox.midtrans.com/v2/qris/mt-1/qr-code"}]}
            """
                .formatted(ORDER_ID)));

    QrisGatewayPort.QrCreated qr = client.createQr(credentials, COMPANY, ORDER_ID, 185_000L);

    assertThat(qr.qrString()).isEqualTo("00020101021226QRIS-PAYLOAD");
    assertThat(qr.qrUrl()).contains("/qr-code");
    assertThat(qr.providerTxnId()).isEqualTo("mt-1");
    // expiry_time is WIB (UTC+7) wall-clock.
    Instant expected =
        LocalDateTime.parse("2026-08-07T17:30:00").atZone(ZoneId.of("Asia/Jakarta")).toInstant();
    assertThat(qr.expiresAt()).isEqualTo(expected);

    RecordedRequest request = midtrans.takeRequest();
    assertThat(request.getPath()).isEqualTo("/v2/charge");
    assertThat(request.getHeader("Authorization"))
        .isEqualTo(
            "Basic " + Base64.getEncoder().encodeToString("SB-Mid-server-abcdef1234:".getBytes()));
    assertThat(request.getHeader("X-Override-Notification"))
        .isEqualTo("https://uat.example.test/api/v1/psp-webhooks/midtrans/" + COMPANY);
    String body = request.getBody().readUtf8();
    assertThat(body).contains("\"payment_type\":\"qris\"");
    assertThat(body).contains("\"order_id\":\"" + ORDER_ID + "\"");
    assertThat(body).contains("\"gross_amount\":185000");
    assertThat(body).contains("\"expiry_duration\":15");
  }

  @Test
  void aDenyAtCreateIsGatewayUnavailableNeverASilentCharge() {
    midtrans.enqueue(json("{\"status_code\":\"202\",\"status_message\":\"denied\"}"));
    assertThatThrownBy(() -> client.createQr(credentials, COMPANY, ORDER_ID, 10_000L))
        .isInstanceOf(GatewayUnavailableException.class);
  }

  @Test
  void aServerErrorAtCreateIsGatewayUnavailable() {
    midtrans.enqueue(new MockResponse().setResponseCode(500));
    assertThatThrownBy(() -> client.createQr(credentials, COMPANY, ORDER_ID, 10_000L))
        .isInstanceOf(GatewayUnavailableException.class);
  }

  @Test
  void aDuplicateOrderIdFallsBackToStatusAndReportsResolveViaSync() {
    midtrans.enqueue(new MockResponse().setResponseCode(406).setBody("{}"));
    midtrans.enqueue(
        json(
            "{\"status_code\":\"200\",\"transaction_status\":\"pending\",\"transaction_id\":\"mt-1\"}"));
    assertThatThrownBy(() -> client.createQr(credentials, COMPANY, ORDER_ID, 10_000L))
        .isInstanceOf(GatewayUnavailableException.class)
        .hasMessageContaining("resolve via sync");
  }

  @Test
  void statusNormalizesTheMidtransVocabulary() {
    midtrans.enqueue(
        json(
            "{\"status_code\":\"200\",\"transaction_status\":\"settlement\",\"transaction_id\":\"mt-2\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.SETTLED);

    midtrans.enqueue(
        json(
            "{\"status_code\":\"200\",\"transaction_status\":\"capture\",\"fraud_status\":\"accept\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.SETTLED);

    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"expire\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.EXPIRED);

    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"deny\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.FAILED);

    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"pending\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.PENDING);

    midtrans.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.NOT_FOUND);

    // Some deployments answer HTTP 200 with a body-level 404.
    midtrans.enqueue(json("{\"status_code\":\"404\",\"status_message\":\"not found\"}"));
    assertThat(client.status(credentials, ORDER_ID).status()).isEqualTo(RemoteStatus.NOT_FOUND);
  }

  @Test
  void cancelDisambiguatesAlreadySettledViaStatus() {
    // A clean cancel.
    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"cancel\"}"));
    assertThat(client.cancel(credentials, ORDER_ID)).isEqualTo(CancelOutcome.CANCELED);

    // 412 "cannot modify" + settlement → the money moved; the caller MUST settle, never swallow.
    midtrans.enqueue(new MockResponse().setResponseCode(412).setBody("{}"));
    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"settlement\"}"));
    assertThat(client.cancel(credentials, ORDER_ID)).isEqualTo(CancelOutcome.ALREADY_SETTLED);

    // 412 + expire → effectively done.
    midtrans.enqueue(new MockResponse().setResponseCode(412).setBody("{}"));
    midtrans.enqueue(json("{\"status_code\":\"200\",\"transaction_status\":\"expire\"}"));
    assertThat(client.cancel(credentials, ORDER_ID)).isEqualTo(CancelOutcome.CANCELED);

    midtrans.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
    assertThat(client.cancel(credentials, ORDER_ID)).isEqualTo(CancelOutcome.NOT_FOUND);
  }

  @Test
  void verifyProbesStatusWithoutChargingAndMapsAuthOutcomes() throws Exception {
    // 404 (order unknown) → the key authenticated.
    midtrans.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.VALID);
    RecordedRequest probe = midtrans.takeRequest();
    // Side-effect-free: a status GET of a throwaway order id — NEVER /v2/charge.
    assertThat(probe.getMethod()).isEqualTo("GET");
    assertThat(probe.getPath()).startsWith("/v2/native-verify-");
    assertThat(probe.getPath()).endsWith("/status");

    // 401 → the key is rejected (wrong key, or a key for the OTHER environment).
    midtrans.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.INVALID);

    // Some deployments answer HTTP 200 with a body-level 401.
    midtrans.enqueue(json("{\"status_code\":\"401\",\"status_message\":\"unauthorized\"}"));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.INVALID);

    // HTTP 200 with a normal (non-401) status body → authenticated.
    midtrans.enqueue(json("{\"status_code\":\"404\",\"status_message\":\"not found\"}"));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.VALID);

    // Provider/transport error → inconclusive, never a false verdict.
    midtrans.enqueue(new MockResponse().setResponseCode(500));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.UNREACHABLE);

    // 403 is an auth rejection too.
    midtrans.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.INVALID);

    // A dropped connection (ResourceAccessException) is inconclusive, never INVALID/VALID.
    midtrans.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    assertThat(client.verify(credentials)).isEqualTo(GatewayVerification.UNREACHABLE);
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }
}
