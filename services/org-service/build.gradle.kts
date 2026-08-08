// org-service (M1.2) — the tenant keystone: owner/account, company (legal employer,
// base_currency [immutable] + default_language), and the org tree (org_unit).
//
// A deployable Spring Boot 4 app cloned from the service-template / restaurant-service
// SHAPE: REST web, JPA persistence over PostgreSQL with a Flyway baseline, auto-applied
// RLS tenant isolation (rule 5), Auditable audit columns, the transactional outbox, and
// lean observability. Creating a company bootstraps a new tenant and emits exactly one
// CompanyCreated event.

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
    //            org-service holds no monetary amount, but base_currency is an
    //            ISO-4217 code; it is validated via java.util.Currency exactly as
    //            Money does, and money stays on the classpath so the no-float
    //            ArchUnit money rule and the shared currency vocabulary apply.
    //   events — OutboxWriter / ProcessedEventStore / AvroSerde plumbing. Exposes
    //            Avro (org.apache.avro) transitively as `api`, so building the
    //            CompanyCreated GenericRecord needs no extra dependency or codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts")) // event Avro schemas: single source of truth
    implementation(project(":libs:tenant"))

    // observability — the SHARED logback-native-json.xml (one-object-per-line JSON logs with the
    // trace/correlation MDC fields + a dev console fallback) this service's logback-spring.xml
    // <include>s, plus the logstash-logback-encoder it references (ENGINEERING-STANDARDS §5). One
    // source of the log format across every service; no copied appender XML.
    implementation(project(":libs:observability"))

    // Defense-in-depth local JWT validation (#16): a shared auto-configuration that, in the
    // non-dev profile, validates the inbound RS256 token against Keycloak's JWKS and binds
    // TenantContext from the verified company_id claim (so RLS engages) — never trusting an
    // inbound X-Company-Id header. In the dev profile it yields to the header-trust DevTenantFilter.
    // Brings spring-boot-starter-oauth2-resource-server + security transitively; no per-service
    // security code.
    implementation(project(":libs:security"))

    // Web (/healthz + the /api/v1/companies endpoints) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on the
    // create-company / create-business bodies rejects malformed input at the edge
    // with a 400 (see ApiExceptionHandler) instead of letting it reach the service.
    // Spring Boot 4 no longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // backs /actuator/prometheus. Versions managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI docs (ADR 0004 / ADR 0008 — fleet rollout). springdoc generates /v3/api-docs (OpenAPI
    // 3.1) and serves /swagger-ui from the live controllers — no hand-maintained spec to drift. The
    // 3.0.x line is the one built for Spring Boot 4 / Framework 7 (2.8.x targets Boot 3 and returns a
    // Base64-mangled /v3/api-docs on Framework 7); the version is catalog-pinned, not in the Boot BOM.
    // In non-dev the docs sit behind the JWT chain and are not gateway-routed (dev/in-cluster only).
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module); flyway-core alone no longer
    // triggers it. flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the
    // @WebMvcTest slice (Spring Boot 4 splits it into its own module), and a real
    // PostgreSQL 16 via Testcontainers — RLS is a PostgreSQL feature no embedded
    // engine can emulate, and the auto-RLS + tenant-bootstrap proofs need the real one.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")

    // The SECURED (non-dev) profile proof — POST /api/v1/companies is the tenant bootstrap, so it
    // must succeed with a valid OWNER token that has NO company_id claim (libs/security declares it
    // tenant-optional). A real Keycloak via Testcontainers issues genuine RS256 tokens (exactly as
    // libs/security's own proof), and okhttp fetches them via the password grant.
    testImplementation("com.github.dasniko:testcontainers-keycloak:3.9.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The existing
// suites bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig and leaves the header-trust DevTenantFilter as the tenant source (no Keycloak
// needed). The SECURED non-dev path is proven separately in libs/security against a real Keycloak.
// Set here (not per-test-class) so no existing test annotation/assertion changes.
// ORG_PII_KEY: the test PII/credential encryption key (a base64 32-byte AES-256 key) for the
// device-credential password (ADR 0049 P3a). In production the key comes from Vault via env; here a
// fixed dev/test key keeps the encrypt/decrypt round-trip and the ciphertext-at-rest assertions
// deterministic. It is NOT a real secret, and is DISTINCT from every other service's test key
// (org-service holds its own PII cipher instance, database-per-service — rule 1; the env var is
// service-namespaced — ORG_PII_KEY, not the shared NATIVE_PII_KEY — so a co-deployed slice cannot
// key two services' ciphers identically: security review P3a LOW-3).
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
    systemProperty(
        "ORG_PII_KEY",
        // A deterministic 32-byte (AES-256) key, base64-encoded — test-only, never a real secret.
        // Decodes to the 32 ASCII bytes "native-org-pii-test-key-01234567".
        "bmF0aXZlLW9yZy1waWktdGVzdC1rZXktMDEyMzQ1Njc=",
    )
}
