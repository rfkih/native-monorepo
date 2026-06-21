// service-template — the reusable starting point every Native service is cloned
// from (M0.3). A deployable Spring Boot 4 app: REST web, JPA persistence over
// PostgreSQL with a Flyway baseline, auto-applied RLS tenant isolation, audit
// columns, and lean observability (actuator + micrometer).

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the
        // Testcontainers BOM into version-less coordinates; import it explicitly
        // (same pinned 2.0.5 the libs use) so org.testcontainers:* resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type (integer minor units + ISO-4217 currency).
    //   events — OutboxWriter / ProcessedEventStore / AvroSerde plumbing.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:tenant"))

    // observability — the SHARED logback-native-json.xml (JSON logs, trace/correlation MDC
    // fields, dev console fallback) that this service's logback-spring.xml <include>s, plus the
    // logstash-logback-encoder it references (ENGINEERING-STANDARDS §5). One source, no copied XML.
    implementation(project(":libs:observability"))

    // Defense-in-depth local JWT validation (#16) AND the shared RFC-7807 ProblemDetail advice
    // (#12): libs/security ships both as Spring Boot auto-configurations, so this service inherits
    // ONE compliant ApiExceptionHandler (and local JWT validation) by dependency — no copied
    // config/ApiExceptionHandler. Brings spring-boot-starter-oauth2-resource-server + security +
    // starter-web transitively. The dev profile yields to the header-trust DevTenantFilter.
    implementation(project(":libs:security"))

    // Web (/healthz controller) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // AOP: the auto-RLS @Aspect (from libs/tenant's TenantRlsAutoConfiguration) that sets the
    // tenant GUC on every @Transactional. Spring Boot 4 renamed the AOP starter to
    // spring-boot-starter-aspectj. libs/tenant also exposes it transitively, but it is named here
    // so the service's auto-RLS does not silently depend on a transitive being present.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // actually backs the exposed /actuator/prometheus scrape endpoint (it pulls in
    // micrometer-core transitively). Version managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI docs (ADR 0004 / ADR 0008 — the fleet standard). springdoc generates /v3/api-docs
    // (OpenAPI 3.1) and serves /swagger-ui from the live controllers — no hand-maintained spec to
    // drift. The version MATTERS: the 3.0.x line is the one built for Spring Boot 4 / Spring
    // Framework 7; the 2.8.x line targets Boot 3 and returns a Base64-mangled /v3/api-docs on
    // Framework 7. NOT in the Boot BOM, so the version is catalog-pinned. In non-dev the docs sit
    // behind the JWT chain and the gateway does not route /v3/api-docs or /swagger-ui — docs stay
    // dev/in-cluster, never public. Inherited by every cloned service so its API self-documents and
    // the @Operation ArchUnit guard (LayeredArchitectureTest) compiles against the swagger annotations.
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module); flyway-core alone no longer
    // triggers it. flyway-database-postgresql registers the PostgreSQL dialect
    // (required from Flyway 10+).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the
    // @WebMvcTest slice (Spring Boot 4 splits it into its own module), and a real
    // PostgreSQL 16 via Testcontainers — RLS is a PostgreSQL feature no embedded
    // engine can emulate, and the auto-RLS proof needs the genuine one.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. Now that the
// template depends on libs/security (for the shared #12 ProblemDetail advice + local JWT
// validation), its existing full-context tests — which bind the tenant directly
// (TenantContext.callAs) and never present a real Keycloak token — run under the `dev` profile,
// which selects libs/security's permissive DevSecurityConfig (no Keycloak needed). The SECURED
// non-dev path is proven in libs/security against a real Keycloak. Set here (not per-test-class) so
// no existing test annotation/assertion changes.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
}
