package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Smoke test: springdoc-openapi 3.0.x generates a valid OpenAPI 3.1 document on Spring Boot 4.1 /
 * Spring Framework 7 (ADR 0004 / ADR 0008 — fleet rollout). Boots the real service and asserts the
 * docs endpoint is genuine OpenAPI JSON documenting the live controllers. Runs under the dev
 * profile (permitAll), so no token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsSmokeTest extends KafkaPostgresTestBase {

  @LocalServerPort private int port;

  @Test
  void apiDocsIsValidOpenApiJsonNotBase64AndDocumentsTheLiveEndpoints() {
    String body =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String.class);

    assertThat(body).isNotNull().startsWith("{").contains("\"openapi\"").contains("\"3.");
    assertThat(body).contains("/api/v1/loyalty/members");
  }
}
