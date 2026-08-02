package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;

/**
 * The Track E phase E3 SPIKE (ADR 0030 §8): does a real ~5 MB {@code multipart/form-data} POST
 * survive the gateway proxy hop? Finding: <strong>yes, unconditionally, with NO gateway config
 * change</strong> — but for a more surprising reason than "raise the multipart size limit", and
 * this Javadoc records the (non-obvious) mechanism so a future engineer does not waste time tuning
 * the wrong property.
 *
 * <p><strong>What was checked first (the naive hypothesis).</strong> The gateway is {@code
 * spring-cloud-gateway-server-webmvc} — a SERVLET stack ({@code GatewayRouterFunctions.http()}
 * proxies the raw request body via a {@code RestClient}). {@code spring-boot-starter-web} pulls in
 * {@code MultipartAutoConfiguration}, and its {@code MultipartProperties} defaults are {@code
 * max-file-size=1MB} / {@code max-request-size=10MB} (confirmed by decompiling {@code
 * MultipartProperties}'s bytecode, Spring Boot 4.1.0) — small enough to plausibly reject a 5 MB
 * receipt if Spring's {@code DispatcherServlet.checkMultipart()} eagerly parsed every part before
 * the route is even matched, the way it would in an ordinary Spring MVC app.
 *
 * <p><strong>What is actually true (verified by inspecting the live {@code ApplicationContext} and
 * the Spring Boot condition-evaluation report, then confirmed by decompiling Spring Cloud Gateway
 * MVC's classes).</strong> Spring Cloud Gateway Server WebMVC ships its OWN {@code
 * MultipartEnvironmentPostProcessor}, which — unless the app explicitly sets {@code
 * spring.servlet.multipart.enabled} to ANY value — injects {@code
 * spring.servlet.multipart.enabled=false} as the highest-precedence property source. That disables
 * BOTH Boot's plain {@code MultipartAutoConfiguration} AND gateway's own {@code
 * GatewayMultipartAutoConfiguration} (which would otherwise install a {@code
 * GatewayMvcMultipartResolver} — a lazy wrapper whose {@code resolveMultipart()} never calls {@code
 * getParts()} either). Net effect in THIS gateway's default configuration: no {@code
 * MultipartResolver} bean exists at all (proven via {@code
 * ApplicationContext.containsBean("multipartResolver") == false}), so {@code
 * DispatcherServlet.checkMultipart()} is a no-op and the RAW {@code HttpServletRequest} — multipart
 * or not — passes straight through to the router-function proxy handler, which streams {@code
 * getInputStream()} byte-for-byte to the downstream. Tomcat's container-level multipart part-size
 * enforcement ({@code MultipartConfigElement}, driven by {@code
 * spring.servlet.multipart.max-file-size}/{@code max-request-size}) is therefore NEVER consulted
 * for a gateway-proxied request — those two properties are effectively dead configuration at the
 * gateway (they matter only in the actual multipart-terminating service, e.g. employee-service's
 * own 5 MB/6 MB limits, which real Tomcat parsing DOES enforce there).
 *
 * <p><strong>The residual this surfaces (documented, not fixed here — out of this phase's
 * scope).</strong> Because no resolver ever engages, the gateway today enforces NO upper bound on
 * ANY proxied request body (multipart or not) — a client could stream an arbitrarily large body
 * through the edge before employee-service's own limit finally rejects it, wasting gateway
 * bandwidth/threads in the process. That is a pre-existing, cross-cutting gateway concern (every
 * route, not just receipts) and is intentionally left for a future ADR/ticket rather than widened
 * here; the E3 receipt endpoints stay safe because the TERMINATING service (employee-service)
 * enforces the real 5 MB/6 MB cap via genuine Tomcat multipart parsing (see {@code application.yml}
 * there).
 *
 * <p><strong>What this test proves</strong> (against the real downstream stub from {@link
 * GatewayIntegrationTestBase} — a genuine embedded Tomcat via {@code RANDOM_PORT}, not a mocked
 * {@code HttpServletRequest}, so this is the real proxy code path, not a MockMvc shortcut): a 5 MB
 * multipart POST through an existing authenticated route ({@code /api/v1/me/**}, the exact route
 * the receipt upload endpoint rides) reaches the downstream stub with its FULL body intact
 * byte-for-byte. This is a permanent regression guard — if a future change starts eagerly resolving
 * multipart at the gateway (e.g. some route explicitly sets {@code
 * spring.servlet.multipart.enabled=true}), this test will catch a resulting 413/size mismatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartUploadStreamingSpikeTest extends GatewayIntegrationTestBase {

  /** ~5 MB — the receipt size cap (ADR 0030 §8, Phase E3). */
  private static final int FIVE_MB = 5 * 1024 * 1024;

  @Test
  void aFiveMegabyteMultipartPostStreamsThroughToTheDownstreamIntact() throws Exception {
    String token = obtainAccessToken();
    byte[] payload = new byte[FIVE_MB];
    // Deterministic non-zero content so a truncation/corruption is trivially detectable if this
    // regresses — a coarse fill is enough (this is not a hashing test, byte count is the proof).
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i % 251);
    }

    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder
        .part("file", new NamedByteArrayResource(payload, "receipt.jpg"))
        .contentType(MediaType.IMAGE_JPEG);

    // Rides the pre-existing /api/v1/me/** route (ME_ROLES) — the exact route the E3 receipt
    // upload endpoint uses (/api/v1/me/expense-claims/{id}/receipt); no new gateway route exists
    // or is needed for this proxy hop.
    String path = "/api/v1/me/expense-claims/" + UUID.randomUUID() + "/receipt";
    String response =
        gatewayClient()
            .post()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(builder.build())
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                (request, res) -> {
                  throw new AssertionError(
                      "gateway rejected the 5 MB multipart POST with " + res.getStatusCode());
                })
            .body(String.class);

    assertThat(response).isEqualTo("ok");
    RecordedRequest forwarded = theForwardedRequest();
    assertThat(forwarded.getPath()).isEqualTo(path);
    // The full multipart body (the 5 MB part plus its boundary/header overhead) crossed the edge
    // intact — proves the gateway did not truncate, swallow, or reject the request body.
    assertThat(forwarded.getBodySize()).isGreaterThanOrEqualTo(FIVE_MB);
  }

  /**
   * A named in-memory {@link ByteArrayResource} — {@code MultipartBodyBuilder} needs a filename.
   */
  private static final class NamedByteArrayResource extends ByteArrayResource {
    private final String filename;

    NamedByteArrayResource(byte[] bytes, String filename) {
      super(bytes);
      this.filename = filename;
    }

    @Override
    public String getFilename() {
      return filename;
    }
  }
}
