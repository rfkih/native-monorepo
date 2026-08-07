// payment-service — QRIS payment modes + PSP charge lifecycle (ADR 0045, supersedes ADR 0007).
// The single owner of "how this company takes digital payments": the payment_settings aggregate
// (mode MANUAL|STATIC|GATEWAY, the merchant's static QRIS image, the merchant's OWN Midtrans
// credentials — AES-256-GCM column-encrypted) and the payment_charge lifecycle (dynamic QRIS per
// transaction against the merchant's Midtrans account, settled by the signed inbound webhook).
//
// A deployable Spring Boot 4 app cloned from service-template via loyalty-service's wiring recipe:
// REST web, JPA persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant
// isolation (rule 5), Auditable audit columns, the transactional outbox (rule 3), and lean
// observability. It inherits the platform auto-configs (RLS/auditing/JWT/RFC-7807) from
// libs/tenant + libs/security — NO copied config.
//
// It is a PRODUCER ONLY: PaymentChargeSucceeded goes out via the transactional outbox in the same
// transaction as the charge's SUCCEEDED transition; the POS verticals consume it and run their
// existing idempotent capture writers. This service consumes NOTHING from Kafka (the webhook +
// charge-status sync are its only inbound signals), so there is no spring-kafka dependency and no
// processed_event table.

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the Testcontainers
        // BOM into version-less coordinates; import it explicitly (same pinned 2.0.5 the libs use)
        // so org.testcontainers:* resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money     — the Money value type (integer minor units + ISO-4217 currency); every charge
    //               amount_minor is Money-shaped, never a float (rule 8).
    //   events    — OutboxWriter (PaymentChargeSucceeded) + AvroSerde plumbing.
    //   contracts — event Avro schemas: single source of truth (ADR 0003).
    //   tenant    — Auditable @MappedSuperclass, TenantContext scoped value, JpaAuditingConfig,
    //               RlsConnectionInitializer + the transaction synchronizer the auto-RLS aspect
    //               drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts"))
    implementation(project(":libs:tenant"))
    implementation(project(":libs:error-inbox"))

    // observability — the SHARED logback-native-json.xml (JSON logs, trace/correlation MDC fields,
    // dev console fallback) that this service's logback-spring.xml <include>s (ENGINEERING-STANDARDS
    // §5). One source, no copied XML.
    implementation(project(":libs:observability"))

    // Defense-in-depth local JWT validation (#16) AND the shared RFC-7807 ProblemDetail advice
    // (#12) as Spring Boot auto-configurations. The anonymous PSP-webhook path is opened via
    // native.security.public-paths + its own signature-verifying filter (ADR 0045), exactly the
    // ADR 0029 self-order recipe. The dev profile yields to the header-trust DevTenantFilter.
    implementation(project(":libs:security"))

    // Web (/healthz + the /api/v1/payment-settings|payment-charges|psp-webhooks surface) + JPA.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on every request body
    // rejects malformed input at the edge with a 400 (via the shared ProblemDetail advice). Spring
    // Boot 4 no longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect (from libs/tenant's TenantRlsAutoConfiguration) that sets the
    // tenant GUC on every @Transactional. Spring Boot 4 renamed the AOP starter to
    // spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry. Versions from the BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI docs (ADR 0004 / ADR 0008 — the fleet standard). The 3.0.x line is the one built for
    // Spring Boot 4 / Framework 7; catalog-pinned, not in the Boot BOM. In non-dev the docs sit
    // behind the JWT chain and are not gateway-routed.
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the dedicated
    // spring-boot-starter-flyway; flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the @WebMvcTest slice
    // (Spring Boot 4 splits it into its own module), and a real PostgreSQL 16 via Testcontainers
    // as the unprivileged app_user — RLS is a PostgreSQL feature no embedded engine can emulate.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // A lightweight stub for the Midtrans Core API edge (the gateway module's idiom): the
    // MidtransClient unit tests and the charge-flow acceptance test point the configurable base
    // URL at it — no real PSP in any test. okhttp is pinned to mockwebserver's own line: the Boot
    // BOM manages a newer okhttp whose internals mockwebserver 4.x can't load
    // (NoClassDefFoundError okhttp3/internal/Util).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The suites
// here bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig (no Keycloak needed). Set here (not per-test-class) so no test
// annotation/assertion changes — exactly how the other services do it.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
    // The credential-encryption AES-256 key is 12-factor externalized (NEVER a literal in
    // application.yml beyond the dev placeholder) — the test task supplies a deterministic 32-byte
    // base64 key so the suite has a reproducible round-trip without touching Vault. Real
    // environments source it from Vault. The loyalty-service idiom, byte-for-byte.
    systemProperty("NATIVE_PII_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
}
