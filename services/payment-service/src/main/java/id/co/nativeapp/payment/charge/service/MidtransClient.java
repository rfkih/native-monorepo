package id.co.nativeapp.payment.charge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.payment.charge.domain.GatewayUnavailableException;
import id.co.nativeapp.payment.config.PaymentProperties;
import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The Midtrans Core API adapter behind {@link QrisGatewayPort} (ADR 0045). Charges are QRIS ({@code
 * payment_type=qris}) on the MERCHANT's own account: every call authenticates with the merchant's
 * decrypted server key via HTTP Basic ({@code base64(serverKey + ":")}), held only in call-scope
 * locals and never logged (rule 6). The base URL follows the merchant's configured {@link
 * ProviderEnvironment} (sandbox vs production; overridable only for the test stub).
 *
 * <p><strong>Per-charge webhook callback.</strong> When {@code native.payment.webhook-base-url} is
 * set, the charge carries {@code X-Override-Notification:
 * <base>/api/v1/psp-webhooks/midtrans/<companyId>} so Midtrans notifies OUR endpoint with the
 * tenant in the path — zero merchant dashboard configuration (the documented fallback is the
 * merchant setting the same URL in their dashboard).
 *
 * <p><strong>No retry on create.</strong> Midtrans dedupes by {@code order_id}: a retried create
 * answers 406 (duplicate), which callers resolve via {@link #status} instead — retrying blind could
 * double-charge on a provider that didn't dedupe.
 *
 * <p><strong>Status normalization.</strong> QRIS settles {@code pending → settlement}; {@code
 * capture} with {@code fraud_status=accept} is accepted defensively (the card path's vocabulary).
 * {@code expire}/{@code cancel}/{@code deny}/{@code failure} map to their terminal states; an
 * unknown order is {@link QrisGatewayPort.RemoteStatus#NOT_FOUND}.
 */
@Component
public class MidtransClient implements QrisGatewayPort {

  /** Midtrans expiry_time / transaction_time format — WIB (UTC+7) wall-clock. */
  private static final DateTimeFormatter MIDTRANS_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final ZoneId MIDTRANS_ZONE = ZoneId.of("Asia/Jakarta");

  private final PaymentProperties properties;
  // Own instance: this mapper only parses Midtrans's response JSON — it needs none of the app's
  // serialization customization, and Boot 4's web stack exposes no default ObjectMapper bean.
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestClient restClient;

  public MidtransClient(PaymentProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.midtrans().connectTimeoutMs());
    factory.setReadTimeout(properties.midtrans().readTimeoutMs());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public QrCreated createQr(
      GatewayCredentials credentials, UUID companyId, String providerOrderId, long amountMinor) {
    Map<String, Object> body =
        Map.of(
            "payment_type", "qris",
            "transaction_details",
                Map.of(
                    "order_id", providerOrderId,
                    // IDR has zero minor-unit digits, so the minor-units long IS the rupiah
                    // amount Midtrans expects (rule 8: never a float on our side; the JSON
                    // number is integral).
                    "gross_amount", amountMinor),
            "qris", Map.of("acquirer", "gopay"),
            "custom_expiry",
                Map.of("expiry_duration", properties.midtrans().expiryMinutes(), "unit", "minute"));

    JsonNode response;
    try {
      var request =
          restClient
              .post()
              .uri(baseUrl(credentials) + "/v2/charge")
              .header("Authorization", basicAuth(credentials.serverKey()))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON);
      String callback = webhookCallbackUrl(companyId);
      if (callback != null) {
        request = request.header("X-Override-Notification", callback);
      }
      response = parse(request.body(body).retrieve().body(String.class));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 406) {
        // Midtrans dedupe: this order_id already exists — the caller resolves via status().
        RemoteCharge existing = status(credentials, providerOrderId);
        if (existing.status() == RemoteStatus.PENDING
            || existing.status() == RemoteStatus.SETTLED) {
          throw new GatewayUnavailableException(
              "Midtrans reports a duplicate order id — resolve via sync");
        }
      }
      throw unavailable("charge create", e);
    } catch (ResourceAccessException e) {
      throw new GatewayUnavailableException("Midtrans unreachable during charge create", e);
    }

    // Midtrans wraps everything in 200/201 with a body-level status_code; 201 = created pending.
    String statusCode = text(response, "status_code");
    if (!"201".equals(statusCode)) {
      throw new GatewayUnavailableException(
          "Midtrans charge create answered status_code=" + statusCode);
    }
    String qrString = text(response, "qr_string");
    if (qrString == null || qrString.isBlank()) {
      throw new GatewayUnavailableException("Midtrans charge create returned no qr_string");
    }
    return new QrCreated(
        qrString,
        qrCodeActionUrl(response),
        text(response, "transaction_id"),
        parseExpiry(text(response, "expiry_time")));
  }

  @Override
  public RemoteCharge status(GatewayCredentials credentials, String providerOrderId) {
    JsonNode response;
    try {
      response =
          parse(
              restClient
                  .get()
                  .uri(baseUrl(credentials) + "/v2/" + providerOrderId + "/status")
                  .header("Authorization", basicAuth(credentials.serverKey()))
                  .accept(MediaType.APPLICATION_JSON)
                  .retrieve()
                  .body(String.class));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new RemoteCharge(RemoteStatus.NOT_FOUND, null);
      }
      throw unavailable("status probe", e);
    } catch (ResourceAccessException e) {
      throw new GatewayUnavailableException("Midtrans unreachable during status probe", e);
    }
    // Some deployments answer HTTP 200 with a body-level 404.
    if ("404".equals(text(response, "status_code"))) {
      return new RemoteCharge(RemoteStatus.NOT_FOUND, null);
    }
    return new RemoteCharge(
        normalize(text(response, "transaction_status"), text(response, "fraud_status")),
        text(response, "transaction_id"));
  }

  @Override
  public CancelOutcome cancel(GatewayCredentials credentials, String providerOrderId) {
    try {
      parse(
          restClient
              .post()
              .uri(baseUrl(credentials) + "/v2/" + providerOrderId + "/cancel")
              .header("Authorization", basicAuth(credentials.serverKey()))
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(String.class));
      return CancelOutcome.CANCELED;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return CancelOutcome.NOT_FOUND;
      }
      if (e.getStatusCode().value() == 412) {
        // "Cannot modify" — the charge already left pending. Disambiguate via status: money moved
        // → the caller MUST run the settlement path (never swallow real money).
        RemoteCharge current = status(credentials, providerOrderId);
        return switch (current.status()) {
          case SETTLED -> CancelOutcome.ALREADY_SETTLED;
          case NOT_FOUND -> CancelOutcome.NOT_FOUND;
          default -> CancelOutcome.CANCELED;
        };
      }
      throw unavailable("cancel", e);
    } catch (ResourceAccessException e) {
      throw new GatewayUnavailableException("Midtrans unreachable during cancel", e);
    }
  }

  private String baseUrl(GatewayCredentials credentials) {
    return credentials.environment() == ProviderEnvironment.PRODUCTION
        ? properties.midtrans().productionBaseUrl()
        : properties.midtrans().sandboxBaseUrl();
  }

  private String webhookCallbackUrl(UUID companyId) {
    String base = properties.webhookBaseUrl();
    if (base == null || base.isBlank()) {
      return null;
    }
    String trimmed = base.strip();
    if (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed + "/api/v1/psp-webhooks/midtrans/" + companyId;
  }

  private static String basicAuth(String serverKey) {
    return "Basic "
        + Base64.getEncoder().encodeToString((serverKey + ":").getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode parse(String body) {
    try {
      if (body == null || body.isBlank()) {
        throw new GatewayUnavailableException("Midtrans answered an empty body");
      }
      return objectMapper.readTree(body);
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      // Never include the raw body (could echo request details) — just the failure class.
      throw new GatewayUnavailableException("Midtrans answered a non-JSON body", e);
    }
  }

  private static String qrCodeActionUrl(JsonNode response) {
    JsonNode actions = response.get("actions");
    if (actions != null && actions.isArray()) {
      for (JsonNode action : actions) {
        if ("generate-qr-code".equals(text(action, "name"))) {
          return text(action, "url");
        }
      }
    }
    return null;
  }

  private Instant parseExpiry(String expiryTime) {
    if (expiryTime == null || expiryTime.isBlank()) {
      return Instant.now().plusSeconds(properties.midtrans().expiryMinutes() * 60L);
    }
    try {
      return LocalDateTime.parse(expiryTime.strip(), MIDTRANS_TIME)
          .atZone(MIDTRANS_ZONE)
          .toInstant();
    } catch (java.time.format.DateTimeParseException e) {
      // A format drift must not fail the charge — fall back to our own window.
      return Instant.now().plusSeconds(properties.midtrans().expiryMinutes() * 60L);
    }
  }

  private static RemoteStatus normalize(String transactionStatus, String fraudStatus) {
    if (transactionStatus == null) {
      return RemoteStatus.PENDING;
    }
    return switch (transactionStatus) {
      case "settlement" -> RemoteStatus.SETTLED;
      case "capture" -> "accept".equals(fraudStatus) ? RemoteStatus.SETTLED : RemoteStatus.PENDING;
      case "expire" -> RemoteStatus.EXPIRED;
      case "cancel" -> RemoteStatus.CANCELED;
      case "deny", "failure" -> RemoteStatus.FAILED;
      default -> RemoteStatus.PENDING;
    };
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static GatewayUnavailableException unavailable(
      String operation, RestClientResponseException e) {
    // Status code only — never the response body (rule 6: no key echo, no PII).
    return new GatewayUnavailableException(
        "Midtrans " + operation + " failed with HTTP " + e.getStatusCode().value(), e);
  }
}
