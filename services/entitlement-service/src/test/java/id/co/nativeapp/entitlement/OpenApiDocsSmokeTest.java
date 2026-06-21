package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Smoke test: springdoc-openapi 3.0.x generates a valid OpenAPI 3.1 document on Spring Boot 4.1 /
 * Spring Framework 7 (ADR 0004 / ADR 0008 — fleet rollout). The 2.8.x line returns /v3/api-docs as
 * a Base64 blob on Framework 7 (fixed only in 3.0.x); this boots the real service and asserts the
 * docs endpoint is genuine OpenAPI JSON documenting the live controllers, so a Boot/springdoc bump
 * that breaks doc generation fails the build. Runs under the dev profile (permitAll), so no token.
 *
 * <p>Extends {@link KafkaPostgresRedisTestBase} (not the lighter {@code PostgresRlsTestBase})
 * because entitlement-service starts a Kafka consumer and a Redis-backed entitlement-check cache as
 * part of its application context; the full context cannot boot without live Kafka and Redis
 * containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsSmokeTest extends KafkaPostgresRedisTestBase {

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
    assertThat(body).contains("/api/v1/entitlements");
  }
}
