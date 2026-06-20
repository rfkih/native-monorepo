package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Smoke test: springdoc-openapi 3.0.x generates a valid OpenAPI 3.1 document on Spring Boot 4.1 /
 * Spring Framework 7.
 *
 * <p>The version pin is the whole point. The springdoc 2.8.x line targets Spring Boot 3 / Framework
 * 6 and, on Framework 7, returns {@code /v3/api-docs} as a BASE64-encoded blob (fixed only in the
 * 3.0.x line — ADR 0004). This test boots the real service and asserts the docs endpoint is genuine
 * OpenAPI JSON, not Base64, AND that springdoc actually scanned the live controllers (the
 * financial-statements paths are present). So a future Boot/springdoc bump that breaks doc
 * generation fails the build here instead of silently shipping a broken {@code /swagger-ui}.
 *
 * <p>Runs under the {@code dev} profile (the test task sets it), whose permissive security chain
 * {@code permitAll}s every request — so the docs endpoint answers without a token. In non-dev the
 * same endpoint sits behind the JWT chain and is not gateway-routed (docs stay dev/in-cluster).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsSmokeTest extends PostgresRlsTestBase {

  @LocalServerPort private int port;

  @Test
  void apiDocsIsValidOpenApiJsonNotBase64AndDocumentsTheStatementsEndpoints() {
    String body =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String.class);

    // Genuine OpenAPI JSON — a real document starts with `{` and carries the `openapi: 3.x` marker.
    // The 2.8.x-on-Framework-7 failure mode is a single Base64 string, which has neither.
    assertThat(body).isNotNull().startsWith("{").contains("\"openapi\"").contains("\"3.");
    // springdoc scanned the live controllers: the financial-statements endpoints are documented.
    assertThat(body)
        .contains("/api/v1/statements/income")
        .contains("/api/v1/statements/balance-sheet");
  }
}
