// restaurant-service (M1.4) — the first vertical, the validation-slice goal.
//
// A deployable Spring Boot 4 app cloned from the service-template SHAPE: REST web,
// JPA persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant
// isolation (rule 5), Auditable audit columns, the transactional outbox, and lean
// observability. It records a sale and emits exactly one SaleRecorded event.

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
    //   events — OutboxWriter / ProcessedEventStore / AvroSerde plumbing. Exposes
    //            Avro (org.apache.avro) transitively as `api`, so building the
    //            SaleRecorded GenericRecord needs no extra dependency or codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts")) // event Avro schemas: single source of truth
    implementation(project(":libs:tenant"))

    // Error-inbox (ADR 0005 pilot -> ADR 0009 fleet rollout): fingerprint-deduped, PII-redacted
    // error_log rows. Activated here for the Phase 5 (ADR 0028) offline-replay stock-discrepancy
    // record (StockDeductionWriter#deductForLinesAllowingNegative) — restaurant-service was
    // originally a "pure producer" exclusion in ADR 0009, but it has since grown a Kafka consumer
    // (UserOutletAssignmentChanged) and now this business-discrepancy write path.
    implementation(project(":libs:error-inbox"))

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

    // The shared Redis-cached entitled?(company, module) gate (Phase 6, ADR 0029 — self-order is
    // this service's first entitlement-gated feature). restaurant-service supplies the DB-backed
    // loader over its LOCAL entitlement projection (kept current by EntitlementGranted/Revoked) and
    // invalidates the cache from those same events. Pulls spring-data-redis (Lettuce) transitively
    // as `api`.
    implementation(project(":libs:entitlement-check"))

    // Object storage (ADR 0048): menu images live in MinIO as content-addressed objects, not
    // inline base64 in menu_item. Auto-configures the MediaStorage bean from native.media.*;
    // this service's credentials are policy-scoped to the restaurant/ prefix.
    implementation(project(":libs:media-storage"))

    // Web (/healthz + POST /sales) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on the
    // POST /sales body rejects malformed input at the edge with a 400 (see
    // ApiExceptionHandler) instead of letting it reach the service. Spring Boot 4
    // no longer pulls validation in transitively via starter-web.
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

    // Kafka consumer (Phase 5 — UserOutletAssignmentChanged listener). Spring Boot 4 bundles
    // spring-kafka into the dedicated spring-boot-starter-kafka module; spring-kafka alone no longer
    // provides the auto-configured KafkaProperties bean that our KafkaConfig reads.
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the
    // @WebMvcTest slice (Spring Boot 4 splits it into its own module), and a real
    // PostgreSQL 16 via Testcontainers — RLS is a PostgreSQL feature no embedded
    // engine can emulate, and the auto-RLS + idempotency proofs need the real one.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")

    // Phase 6 (ADR 0029) entitlement-gate tests: a real Kafka broker (publish/await the consumed
    // EntitlementGranted/Revoked Avro bytes) and a real Redis 7 (the entitlement-check cache gate) —
    // mirrors barbershop-service's/carwash-service's KafkaPostgresRedisTestBase exactly. Redis is a
    // plain GenericContainer from the core Testcontainers module (no dedicated Redis module here).
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The existing
// suites bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig and leaves the header-trust DevTenantFilter as the tenant source (no Keycloak
// needed). The SECURED non-dev path is proven separately in libs/security against a real Keycloak.
// Set here (not per-test-class) so no existing test annotation/assertion changes.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
    // Security review W-4: the gift-card code-derivation HMAC key. MUST be the IDENTICAL value in
    // every other service's test task (loyalty/carwash/barbershop-service) — see
    // GiftCardCodeGenerator's class javadoc for why a fleet-wide key match matters.
    systemProperty("NATIVE_GIFTCARD_CODE_KEY", "Z2lmdC1jYXJkLWNvZGUtaG1hYy1rZXktMDEyMzQ1Njc=")
}
