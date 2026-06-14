// gateway (M1.1) — the single synchronous edge.
//
// A STATELESS Spring Boot 4 app: NO database, NO JPA, NO RLS/Auditable. It clones
// only the SHAPE of service-template (conventions, Dockerfile, /healthz), not its
// persistence stack. Its job is narrow and security-critical:
//   - validate inbound RS256 JWTs against Keycloak's JWKS (OAuth2 resource server);
//   - inject the validated tenant context downstream as trusted headers
//     (X-Company-Id / X-Actor / X-Roles), stripping any client-supplied copies;
//   - route /api/v1/** to the owning service (Spring Cloud Gateway MVC);
//   - Redis-backed token-bucket rate limit per (company_id + sub).
//
// DEPENDENCY DECISION: Spring Cloud Gateway *server-webmvc* (the servlet-stack
// gateway, 5.0.x from the 2025.1.x release train) is used here. It runs on the
// same blocking servlet stack as every other Native service (spring-boot-starter-web),
// so it composes with spring-boot-starter-oauth2-resource-server and the Boot 4.1.0
// BOM cleanly — unlike the reactive spring-cloud-starter-gateway, which would drag in
// WebFlux. The Spring Cloud BOM 2025.1.x is the release train aligned to Boot 4.x.

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // Spring Cloud release train aligned to Spring Boot 4.x (Spring Framework 7).
        // It governs the spring-cloud-gateway-server-webmvc version (5.0.x); the
        // Spring Boot 4.1.0 BOM (applied by native.spring-boot-app) governs Spring,
        // Spring Security, and the OAuth2 resource server.
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.2")
    }
}

dependencies {
    // Spring Cloud Gateway on the SERVLET stack (server-webmvc). Pulls in
    // spring-boot-starter-web transitively, so the gateway runs on the same blocking
    // model as the downstream services — no WebFlux.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")

    // The single sync security edge: validate RS256 JWTs against Keycloak's JWKS.
    // This is a RESOURCE SERVER (it validates bearer tokens), not a login client.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Bean Validation for @ConfigurationProperties (route targets, rate-limit knobs)
    // so the app fails fast at startup on missing/invalid config (ENGINEERING-STANDARDS §7).
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Redis-backed token-bucket rate limit (RequestRateLimiter + RedisRateLimiter),
    // keyed per (company_id + sub). server-webmvc ships the filter; the Redis rate
    // limiter implementation lives in the reactive gateway-server module, which is
    // pulled in transitively by the BOM-managed coordinate below.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Lean observability: actuator probes + the Prometheus scrape endpoint, identical
    // in shape to every other Native service. Versions from the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc) + Spring Security
    // test support, plus Testcontainers — a real Keycloak (issues genuine RS256 tokens
    // against an imported realm) and a real Redis (proves the token bucket). MockWebServer
    // stands in for a downstream service so header injection / spoof-stripping is asserted
    // without booting another Native app.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.github.dasniko:testcontainers-keycloak:3.9.0")
    // Redis runs as a plain GenericContainer (redis:7-alpine) from the core
    // Testcontainers module — no separate Redis module coordinate needed.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility")
}
